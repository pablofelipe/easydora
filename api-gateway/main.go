package main

import (
	"github.com/gin-gonic/gin"
	"log"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"time"
	"encoding/json"
)

// Configuração dos serviços
type ServiceConfig struct {
	URL         string
	Name        string
	Implemented bool
}

var (
	services = map[string]ServiceConfig{
		"auth": {
			URL:         getEnv("AUTH_SERVICE_URL", "http://auth-service:8081"),
			Name:        "auth-service",
			Implemented: true,
		},
		"products": {
			URL:         getEnv("PRODUCTS_SERVICE_URL", "http://products-service:8082"),
			Name:        "products-service",
			Implemented: true,
		},
		"inventory": {
			URL:         getEnv("INVENTORY_SERVICE_URL", "http://inventory-service:8083"),
			Name:        "inventory-service", 
			Implemented: true,
		},
		"orders": {
			URL:         getEnv("ORDERS_SERVICE_URL", "http://orders-service:8084"),
			Name:        "orders-service",
			Implemented: true,
		},
		"billing": {
			URL:         getEnv("BILLING_SERVICE_URL", "http://billing-service:8085"),
			Name:        "billing-service",
			Implemented: true,
		},
	}
)

func main() {
	router := gin.Default()

	// Health check endpoints
	router.GET("/health", healthCheck)
	router.GET("/ping", ping)

	setupServiceRoutes(router)

	port := getEnv("PORT", "8080")
	log.Printf("🚀 API Gateway starting on port %s", port)
	
	if err := router.Run(":" + port); err != nil {
		log.Fatalf("Failed to start server: %v", err)
	}
}

func setupServiceRoutes(router *gin.Engine) {
	for path, config := range services {
		serviceGroup := router.Group("/" + path)
		
		if config.Implemented && config.URL != "" {
			// Serviço implementado - usar reverse proxy
			serviceGroup.Any("/*proxyPath", createReverseProxy(config.URL, config.Name))
			log.Printf("%s proxy configured: %s", config.Name, config.URL)
		} else {
			// Serviço não implementado - usar mock
			serviceGroup.Any("/*proxyPath", createMockHandler(config.Name))
			log.Printf("%s not implemented - using mock responses", config.Name)
		}
	}
}

// Handler genérico para serviços não implementados
func createMockHandler(serviceName string) gin.HandlerFunc {
	return func(c *gin.Context) {
		c.JSON(503, gin.H{
			"error":      "Service temporarily unavailable",
			"service":    serviceName,
			"status":     "not_implemented",
			"message":    "Service is not yet implemented",
			"timestamp":  time.Now().Format(time.RFC3339),
			"path":       c.Request.URL.Path,
			"method":     c.Request.Method,
		})
	}
}

// Reverse proxy para serviços implementados
func createReverseProxy(target, serviceName string) gin.HandlerFunc {
	return func(c *gin.Context) {
		targetURL, err := url.Parse(target)
		if err != nil {
			log.Printf("Error parsing target URL %s: %v", target, err)
			c.JSON(500, gin.H{
				"error":   "Invalid service configuration",
				"service": serviceName,
				"target":  target,
			})
			return
		}

		proxy := httputil.NewSingleHostReverseProxy(targetURL)
		
		// Configurar error handler para debug
		proxy.ErrorHandler = func(w http.ResponseWriter, r *http.Request, err error) {
			log.Printf("Proxy error for %s: %v", serviceName, err)
			w.WriteHeader(http.StatusBadGateway)
			json.NewEncoder(w).Encode(gin.H{
				"error":   "Service unavailable",
				"service": serviceName,
				"details": err.Error(),
			})
		}

		proxy.Transport = &http.Transport{
			ResponseHeaderTimeout: 30 * time.Second,
			DialContext: (&net.Dialer{
				Timeout:   10 * time.Second,
				KeepAlive: 30 * time.Second,
			}).DialContext,
		}

		originalPath := c.Request.URL.Path
		proxyPath := c.Param("proxyPath")
		
		// Log detalhado
		log.Printf("Proxying %s %s → %s%s", 
			c.Request.Method, originalPath, targetURL.Host, proxyPath)

		c.Request.URL.Scheme = targetURL.Scheme
		c.Request.URL.Host = targetURL.Host
		c.Request.URL.Path = proxyPath
		c.Request.Host = targetURL.Host

		// Headers para tracing
		c.Request.Header.Set("X-Forwarded-Host", c.Request.Host)
		c.Request.Header.Set("X-Origin-Service", serviceName)
		c.Request.Header.Set("X-Gateway-Service", "api-gateway")

		proxy.ServeHTTP(c.Writer, c.Request)
	}
}

func healthCheck(c *gin.Context) {
	// Status dos serviços
	servicesStatus := make(map[string]string)
	for path, config := range services {
		if config.Implemented && config.URL != "" {
			servicesStatus[path] = "implemented"
		} else {
			servicesStatus[path] = "not_implemented"
		}
	}

	status := gin.H{
		"status":    "OK",
		"service":   "api-gateway",
		"timestamp": time.Now().Format(time.RFC3339),
		"services":  servicesStatus,
	}

	c.JSON(200, status)
}

func ping(c *gin.Context) {
	c.JSON(200, gin.H{
		"message":   "pong from api gateway",
		"service":   "api-gateway",
		"timestamp": time.Now().Format(time.RFC3339),
	})
}

func getEnv(key, defaultValue string) string {
	value := os.Getenv(key)
	if value == "" {
		return defaultValue
	}
	return value
}