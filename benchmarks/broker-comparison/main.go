// Command broker-comparison is a standalone benchmark tool (not part of any
// EasyDora service) that measures RabbitMQ and Kafka side by side on the
// exact same machine and network, for ADR-0041: throughput, publish/
// end-to-end latency percentiles, and behavior while the broker container is
// stopped and restarted mid-run. Numbers are printed as JSON to stdout and
// also written to a results file so they can be committed to the repo raw,
// not just summarized.
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"os"
	"sort"
	"sync"
	"sync/atomic"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
	"github.com/segmentio/kafka-go"
)

type message struct {
	Seq     int64 `json:"seq"`
	SentUTC int64 `json:"sentUtc"` // UnixNano
}

// ---------- RabbitMQ ----------

var rabbitURL = "amqp://admin:PWD@localhost:5677/"

const rabbitExchange = "benchmark.exchange"
const rabbitQueue = "benchmark.queue"
const rabbitRoutingKey = "benchmark"

func rabbitSetup() (*amqp.Connection, *amqp.Channel) {
	conn, err := amqp.Dial(rabbitURL)
	if err != nil {
		log.Fatalf("rabbitmq dial: %v", err)
	}
	ch, err := conn.Channel()
	if err != nil {
		log.Fatalf("rabbitmq channel: %v", err)
	}
	if err := ch.ExchangeDeclare(rabbitExchange, "topic", true, false, false, false, nil); err != nil {
		log.Fatalf("rabbitmq exchange declare: %v", err)
	}
	if _, err := ch.QueueDeclare(rabbitQueue, true, false, false, false, nil); err != nil {
		log.Fatalf("rabbitmq queue declare: %v", err)
	}
	if err := ch.QueueBind(rabbitQueue, rabbitRoutingKey, rabbitExchange, false, nil); err != nil {
		log.Fatalf("rabbitmq queue bind: %v", err)
	}
	return conn, ch
}

func rabbitPublisherChannel(conn *amqp.Connection) *amqp.Channel {
	ch, err := conn.Channel()
	if err != nil {
		log.Fatalf("rabbitmq publisher channel: %v", err)
	}
	if err := ch.Confirm(false); err != nil {
		log.Fatalf("rabbitmq confirm mode: %v", err)
	}
	return ch
}

// ---------- Kafka ----------

var kafkaBrokerAddr = "localhost:29093"

// kafkaTopic is unique per process run -- reusing one topic across runs
// means StartOffset: FirstOffset replays every prior run's leftover
// messages into the fresh consumer, corrupting this run's latency numbers
// with multi-minute-old timestamps from a previous invocation.
var kafkaTopic = fmt.Sprintf("benchmark-topic-%d", time.Now().UnixNano())

// ensureKafkaTopic sends one throwaway write and retries it for a few
// seconds -- AllowAutoTopicCreation on the writer races the first produce
// against the broker's own lazy topic creation, so the first attempt (or
// several) legitimately fails with "Unknown Topic Or Partition" until the
// broker finishes creating it. Doing that warm-up write here, before the
// timed benchmark section starts, keeps that one-time race out of the
// measured numbers instead of trying to preempt it via topic-metadata
// APIs, which route through the cluster's advertised controller address
// (unreachable by hostname from outside Docker in this single-node setup).
func ensureKafkaTopic() {
	writer := kafkaWriter()
	defer writer.Close()

	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		err := writer.WriteMessages(ctx, kafka.Message{Key: []byte("warmup"), Value: []byte("warmup")})
		cancel()
		if err == nil {
			return
		}
		time.Sleep(300 * time.Millisecond)
	}
	log.Fatalf("kafka topic %q did not become ready in time", kafkaTopic)
}

func kafkaWriter() *kafka.Writer {
	return &kafka.Writer{
		Addr:                   kafka.TCP(kafkaBrokerAddr),
		Topic:                  kafkaTopic,
		Balancer:               &kafka.Hash{},
		RequiredAcks:           kafka.RequireOne,
		AllowAutoTopicCreation: true,
		// Default BatchTimeout is 1s -- fine for a fire-and-forget async
		// producer, but this benchmark calls WriteMessages synchronously
		// once per message specifically to measure per-publish latency, so
		// a 1s default would dominate every sample instead of measuring
		// the broker's real round-trip.
		BatchTimeout: 10 * time.Millisecond,
	}
}

// kafkaReader reads a single partition directly from its start offset,
// bypassing consumer-group coordination entirely -- this benchmark has one
// reader per run and needs its throughput/latency numbers unaffected by
// group-rebalance timing, which is a separate concern kafka-go otherwise
// adds by default.
func kafkaReader() *kafka.Reader {
	return kafka.NewReader(kafka.ReaderConfig{
		Brokers:     []string{kafkaBrokerAddr},
		Topic:       kafkaTopic,
		Partition:   0,
		StartOffset: kafka.FirstOffset,
		MinBytes:    1,
		MaxBytes:    10e6,
	})
}

// ---------- shared stats ----------

type latencyStats struct {
	mu      sync.Mutex
	samples []float64 // milliseconds
}

func (s *latencyStats) add(ms float64) {
	s.mu.Lock()
	s.samples = append(s.samples, ms)
	s.mu.Unlock()
}

func percentile(samples []float64, p float64) float64 {
	if len(samples) == 0 {
		return 0
	}
	sorted := make([]float64, len(samples))
	copy(sorted, samples)
	sort.Float64s(sorted)
	idx := int(p / 100 * float64(len(sorted)-1))
	return sorted[idx]
}

type report struct {
	Broker              string  `json:"broker"`
	Mode                string  `json:"mode"`
	MessageCount        int64   `json:"messageCount"`
	ProducerDurationMs  float64 `json:"producerDurationMs"`
	ProducerThroughput  float64 `json:"producerThroughputMsgsPerSec"`
	ConsumerReceived    int64   `json:"consumerReceived"`
	ConsumerDurationMs  float64 `json:"consumerDurationMs,omitempty"`
	ConsumerThroughput  float64 `json:"consumerThroughputMsgsPerSec,omitempty"`
	PublishLatencyP50Ms float64 `json:"publishLatencyP50Ms"`
	PublishLatencyP99Ms float64 `json:"publishLatencyP99Ms"`
	E2ELatencyP50Ms     float64 `json:"e2eLatencyP50Ms"`
	E2ELatencyP99Ms     float64 `json:"e2eLatencyP99Ms"`
}

// ---------- throughput mode ----------

func runThroughputRabbit(count int64) report {
	conn, setupCh := rabbitSetup()
	defer conn.Close()
	defer setupCh.Close()

	// Drain any leftovers from a previous run so this run starts clean.
	setupCh.QueuePurge(rabbitQueue, false)

	pubCh := rabbitPublisherChannel(conn)
	defer pubCh.Close()

	consumeConn, err := amqp.Dial(rabbitURL)
	if err != nil {
		log.Fatalf("rabbitmq consumer dial: %v", err)
	}
	defer consumeConn.Close()
	consumeCh, err := consumeConn.Channel()
	if err != nil {
		log.Fatalf("rabbitmq consumer channel: %v", err)
	}
	deliveries, err := consumeCh.Consume(rabbitQueue, "", true, false, false, false, nil)
	if err != nil {
		log.Fatalf("rabbitmq consume: %v", err)
	}

	e2e := &latencyStats{}
	var received int64
	var consumerStart, consumerEnd time.Time
	consumerDone := make(chan struct{})
	go func() {
		defer close(consumerDone)
		for d := range deliveries {
			var m message
			if err := json.Unmarshal(d.Body, &m); err != nil {
				continue
			}
			now := time.Now()
			if received == 0 {
				consumerStart = now
			}
			e2e.add(float64(now.UnixNano()-m.SentUTC) / 1e6)
			atomic.AddInt64(&received, 1)
			consumerEnd = now
			if atomic.LoadInt64(&received) >= count {
				return
			}
		}
	}()

	time.Sleep(300 * time.Millisecond) // let the consumer attach

	pubLatency := &latencyStats{}
	producerStart := time.Now()
	for i := int64(0); i < count; i++ {
		m := message{Seq: i, SentUTC: time.Now().UnixNano()}
		body, _ := json.Marshal(m)
		t0 := time.Now()
		confirmation, err := pubCh.PublishWithDeferredConfirmWithContext(context.Background(),
			rabbitExchange, rabbitRoutingKey, false, false,
			amqp.Publishing{ContentType: "application/json", DeliveryMode: amqp.Persistent, Body: body})
		if err != nil {
			log.Printf("publish error: %v", err)
			continue
		}
		confirmation.Wait()
		pubLatency.add(float64(time.Since(t0).Microseconds()) / 1000.0)
	}
	producerDuration := time.Since(producerStart)

	select {
	case <-consumerDone:
	case <-time.After(30 * time.Second):
		log.Printf("rabbitmq consumer timed out waiting for all messages, received=%d/%d", atomic.LoadInt64(&received), count)
	}

	r := report{
		Broker:              "rabbitmq",
		Mode:                "throughput",
		MessageCount:        count,
		ProducerDurationMs:  float64(producerDuration.Milliseconds()),
		ProducerThroughput:  float64(count) / producerDuration.Seconds(),
		ConsumerReceived:    atomic.LoadInt64(&received),
		PublishLatencyP50Ms: percentile(pubLatency.samples, 50),
		PublishLatencyP99Ms: percentile(pubLatency.samples, 99),
		E2ELatencyP50Ms:     percentile(e2e.samples, 50),
		E2ELatencyP99Ms:     percentile(e2e.samples, 99),
	}
	if !consumerEnd.IsZero() {
		r.ConsumerDurationMs = float64(consumerEnd.Sub(consumerStart).Milliseconds())
		if r.ConsumerDurationMs > 0 {
			r.ConsumerThroughput = float64(received) / (r.ConsumerDurationMs / 1000.0)
		}
	}
	return r
}

func runThroughputKafka(count int64) report {
	ensureKafkaTopic()
	writer := kafkaWriter()
	defer writer.Close()

	reader := kafkaReader()
	defer reader.Close()

	e2e := &latencyStats{}
	var received int64
	var consumerStart, consumerEnd time.Time
	consumerDone := make(chan struct{})
	go func() {
		defer close(consumerDone)
		for {
			ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
			msg, err := reader.ReadMessage(ctx)
			cancel()
			if err != nil {
				return
			}
			var m message
			if err := json.Unmarshal(msg.Value, &m); err != nil {
				continue
			}
			now := time.Now()
			if received == 0 {
				consumerStart = now
			}
			e2e.add(float64(now.UnixNano()-m.SentUTC) / 1e6)
			atomic.AddInt64(&received, 1)
			consumerEnd = now
			if atomic.LoadInt64(&received) >= count {
				return
			}
		}
	}()

	time.Sleep(2 * time.Second) // let the Kafka consumer group join before producing

	pubLatency := &latencyStats{}
	producerStart := time.Now()
	for i := int64(0); i < count; i++ {
		m := message{Seq: i, SentUTC: time.Now().UnixNano()}
		body, _ := json.Marshal(m)
		t0 := time.Now()
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		err := writer.WriteMessages(ctx, kafka.Message{
			Key:   []byte(fmt.Sprintf("%d", i)),
			Value: body,
		})
		cancel()
		if err != nil {
			log.Printf("publish error: %v", err)
			continue
		}
		pubLatency.add(float64(time.Since(t0).Microseconds()) / 1000.0)
	}
	producerDuration := time.Since(producerStart)

	select {
	case <-consumerDone:
	case <-time.After(30 * time.Second):
		log.Printf("kafka consumer timed out waiting for all messages, received=%d/%d", atomic.LoadInt64(&received), count)
	}

	r := report{
		Broker:              "kafka",
		Mode:                "throughput",
		MessageCount:        count,
		ProducerDurationMs:  float64(producerDuration.Milliseconds()),
		ProducerThroughput:  float64(count) / producerDuration.Seconds(),
		ConsumerReceived:    atomic.LoadInt64(&received),
		PublishLatencyP50Ms: percentile(pubLatency.samples, 50),
		PublishLatencyP99Ms: percentile(pubLatency.samples, 99),
		E2ELatencyP50Ms:     percentile(e2e.samples, 50),
		E2ELatencyP99Ms:     percentile(e2e.samples, 99),
	}
	if !consumerEnd.IsZero() {
		r.ConsumerDurationMs = float64(consumerEnd.Sub(consumerStart).Milliseconds())
		if r.ConsumerDurationMs > 0 {
			r.ConsumerThroughput = float64(received) / (r.ConsumerDurationMs / 1000.0)
		}
	}
	return r
}

// ---------- failover mode ----------
// Publishes at a fixed rate for `duration`, tagging each message with a
// monotonic sequence number, while the operator stops and restarts the
// broker container mid-run (docker compose stop/start) from outside this
// process. Reports: how many publishes failed (and for how long), whether
// any already-consumed-position message never arrived at all (permanent
// loss) versus merely delayed, and how long after the broker becoming
// reachable again the first successful publish/consume happened.

type failoverReport struct {
	Broker                string  `json:"broker"`
	Mode                  string  `json:"mode"`
	DurationSec           float64 `json:"durationSec"`
	Attempted             int64   `json:"attempted"`
	PublishSucceeded      int64   `json:"publishSucceeded"`
	PublishFailed         int64   `json:"publishFailed"`
	ConsumerReceived      int64   `json:"consumerReceived"`
	LostSequences         []int64 `json:"lostSequences"`
	FirstPublishFailureMs int64   `json:"firstPublishFailureUnixMs,omitempty"`
	LastPublishFailureMs  int64   `json:"lastPublishFailureUnixMs,omitempty"`
	PublishOutageMs       int64   `json:"publishOutageMs"`
}

func runFailoverRabbit(duration time.Duration, rate int) failoverReport {
	conn, setupCh := rabbitSetup()
	defer conn.Close()
	defer setupCh.Close()
	setupCh.QueuePurge(rabbitQueue, false)
	if err := setupCh.Confirm(false); err != nil {
		log.Fatalf("rabbitmq confirm mode: %v", err)
	}

	consumeConn, err := amqp.Dial(rabbitURL)
	if err != nil {
		log.Fatalf("rabbitmq consumer dial: %v", err)
	}
	defer consumeConn.Close()
	consumeCh, err := consumeConn.Channel()
	if err != nil {
		log.Fatalf("rabbitmq consumer channel: %v", err)
	}
	deliveries, err := consumeCh.Consume(rabbitQueue, "", true, false, false, false, nil)
	if err != nil {
		log.Fatalf("rabbitmq consume: %v", err)
	}

	received := map[int64]bool{}
	var recvMu sync.Mutex
	consumerDone := make(chan struct{})
	go func() {
		defer close(consumerDone)
		for d := range deliveries {
			var m message
			if err := json.Unmarshal(d.Body, &m); err != nil {
				continue
			}
			recvMu.Lock()
			received[m.Seq] = true
			recvMu.Unlock()
		}
	}()

	var attempted, succeeded, failed int64
	var firstFail, lastFail time.Time
	var failMu sync.Mutex

	deadline := time.Now().Add(duration)
	ticker := time.NewTicker(time.Second / time.Duration(rate))
	defer ticker.Stop()
	var seq int64
	for time.Now().Before(deadline) {
		<-ticker.C
		m := message{Seq: seq, SentUTC: time.Now().UnixNano()}
		body, _ := json.Marshal(m)
		atomic.AddInt64(&attempted, 1)

		// Reconnect-on-demand: a real caller (RabbitMQPublisherAdapter) does
		// this too -- ensure a live channel before every publish so a
		// mid-run broker restart is recoverable within this same process.
		if conn.IsClosed() {
			for {
				newConn, err := amqp.Dial(rabbitURL)
				if err == nil {
					conn = newConn
					break
				}
				time.Sleep(200 * time.Millisecond)
			}
		}
		if setupCh.IsClosed() {
			newCh, err := conn.Channel()
			if err == nil {
				setupCh = newCh
				setupCh.Confirm(false)
			}
		}

		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		confirmation, err := setupCh.PublishWithDeferredConfirmWithContext(ctx,
			rabbitExchange, rabbitRoutingKey, false, false,
			amqp.Publishing{ContentType: "application/json", DeliveryMode: amqp.Persistent, Body: body})
		var ok bool
		if err == nil && confirmation != nil {
			ok = confirmation.Wait()
		}
		cancel()

		if err != nil || !ok {
			atomic.AddInt64(&failed, 1)
			failMu.Lock()
			if firstFail.IsZero() {
				firstFail = time.Now()
			}
			lastFail = time.Now()
			failMu.Unlock()
			// try again with the same seq isn't done here on purpose --
			// counting this attempt as failed is the point of the test.
		} else {
			atomic.AddInt64(&succeeded, 1)
		}
		seq++
	}

	time.Sleep(3 * time.Second) // drain grace period
	consumeCh.Close()
	<-consumerDone

	var lost []int64
	recvMu.Lock()
	for i := int64(0); i < seq; i++ {
		if !received[i] {
			lost = append(lost, i)
		}
	}
	recvCount := int64(len(received))
	recvMu.Unlock()

	fr := failoverReport{
		Broker:           "rabbitmq",
		Mode:             "failover",
		DurationSec:      duration.Seconds(),
		Attempted:        attempted,
		PublishSucceeded: succeeded,
		PublishFailed:    failed,
		ConsumerReceived: recvCount,
		LostSequences:    lost,
	}
	if !firstFail.IsZero() {
		fr.FirstPublishFailureMs = firstFail.UnixMilli()
		fr.LastPublishFailureMs = lastFail.UnixMilli()
		fr.PublishOutageMs = lastFail.Sub(firstFail).Milliseconds()
	}
	return fr
}

func runFailoverKafka(duration time.Duration, rate int) failoverReport {
	ensureKafkaTopic()
	writer := kafkaWriter()
	defer writer.Close()

	reader := kafkaReader()
	defer reader.Close()

	received := map[int64]bool{}
	var recvMu sync.Mutex
	consumerDone := make(chan struct{})
	go func() {
		defer close(consumerDone)
		for {
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			msg, err := reader.ReadMessage(ctx)
			cancel()
			if err != nil {
				return
			}
			var m message
			if err := json.Unmarshal(msg.Value, &m); err != nil {
				continue
			}
			recvMu.Lock()
			received[m.Seq] = true
			recvMu.Unlock()
		}
	}()

	time.Sleep(2 * time.Second)

	var attempted, succeeded, failed int64
	var firstFail, lastFail time.Time
	var failMu sync.Mutex

	deadline := time.Now().Add(duration)
	ticker := time.NewTicker(time.Second / time.Duration(rate))
	defer ticker.Stop()
	var seq int64
	for time.Now().Before(deadline) {
		<-ticker.C
		m := message{Seq: seq, SentUTC: time.Now().UnixNano()}
		body, _ := json.Marshal(m)
		atomic.AddInt64(&attempted, 1)

		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		err := writer.WriteMessages(ctx, kafka.Message{Key: []byte(fmt.Sprintf("%d", seq)), Value: body})
		cancel()

		if err != nil {
			atomic.AddInt64(&failed, 1)
			failMu.Lock()
			if firstFail.IsZero() {
				firstFail = time.Now()
			}
			lastFail = time.Now()
			failMu.Unlock()
		} else {
			atomic.AddInt64(&succeeded, 1)
		}
		seq++
	}

	time.Sleep(5 * time.Second)
	reader.Close()
	<-consumerDone

	var lost []int64
	recvMu.Lock()
	for i := int64(0); i < seq; i++ {
		if !received[i] {
			lost = append(lost, i)
		}
	}
	recvCount := int64(len(received))
	recvMu.Unlock()

	fr := failoverReport{
		Broker:           "kafka",
		Mode:             "failover",
		DurationSec:      duration.Seconds(),
		Attempted:        attempted,
		PublishSucceeded: succeeded,
		PublishFailed:    failed,
		ConsumerReceived: recvCount,
		LostSequences:    lost,
	}
	if !firstFail.IsZero() {
		fr.FirstPublishFailureMs = firstFail.UnixMilli()
		fr.LastPublishFailureMs = lastFail.UnixMilli()
		fr.PublishOutageMs = lastFail.Sub(firstFail).Milliseconds()
	}
	return fr
}

func main() {
	broker := flag.String("broker", "rabbitmq", "rabbitmq|kafka")
	mode := flag.String("mode", "throughput", "throughput|failover")
	count := flag.Int64("count", 20000, "message count (throughput mode)")
	duration := flag.Duration("duration", 60*time.Second, "run duration (failover mode)")
	rate := flag.Int("rate", 20, "messages/sec (failover mode)")
	out := flag.String("out", "", "optional file to append the JSON result line to")
	rabbitAddr := flag.String("rabbit-url", rabbitURL, "RabbitMQ AMQP URL")
	kafkaAddr := flag.String("kafka-addr", kafkaBrokerAddr, "Kafka bootstrap broker address")
	flag.Parse()
	rabbitURL = *rabbitAddr
	kafkaBrokerAddr = *kafkaAddr

	var result any
	switch *mode {
	case "throughput":
		switch *broker {
		case "rabbitmq":
			result = runThroughputRabbit(*count)
		case "kafka":
			result = runThroughputKafka(*count)
		default:
			log.Fatalf("unknown broker %q", *broker)
		}
	case "failover":
		switch *broker {
		case "rabbitmq":
			result = runFailoverRabbit(*duration, *rate)
		case "kafka":
			result = runFailoverKafka(*duration, *rate)
		default:
			log.Fatalf("unknown broker %q", *broker)
		}
	default:
		log.Fatalf("unknown mode %q", *mode)
	}

	encoded, _ := json.MarshalIndent(result, "", "  ")
	fmt.Println(string(encoded))

	if *out != "" {
		f, err := os.OpenFile(*out, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0644)
		if err == nil {
			defer f.Close()
			line, _ := json.Marshal(result)
			f.Write(line)
			f.WriteString("\n")
		}
	}
}
