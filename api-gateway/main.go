package main

import (
	"github.com/gin-gonic/gin"
	"log"
	"os"
)

func main() {
	router := gin.Default()

	// Health check endpoint
	router.GET("/ping", func(c *gin.Context) {
		c.JSON(200, gin.H{
			"message": "pong from api gateway",
			"service": "api-gateway",
		})
	})

	router.GET("/health", func(c *gin.Context) {
		c.JSON(200, gin.H{
			"status": "OK",
			"service": "api-gateway",
		})
	})

	// Service routes (will be implemented later)
	router.GET("/auth/*path", func(c *gin.Context) {
		c.JSON(200, gin.H{
			"message": "auth service route - to be implemented",
		})
	})

	router.GET("/products/*path", func(c *gin.Context) {
		c.JSON(200, gin.H{
			"message": "products service route - to be implemented",
		})
	})

	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	log.Printf("🚀 API Gateway starting on port %s", port)
	if err := router.Run(":" + port); err != nil {
		log.Fatalf("Failed to start server: %v", err)
	}
}