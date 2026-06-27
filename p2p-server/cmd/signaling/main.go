package main

import (
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"

	"github.com/gin-gonic/gin"

	"github.com/Triddov/p2p-server/config"
	"github.com/Triddov/p2p-server/internal/database"
	"github.com/Triddov/p2p-server/internal/signaling"
)

func main() {
	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("Failed to load config: %v", err)
	}

	// Connect to Postgre
	db, err := database.NewPostgres(cfg.GetDSN())
	if err != nil {
		log.Fatalf("Failed to connect to database: %v", err)
	}
	defer db.Close()

	log.Println("Connected to PostgreSQL")

	// Create hub
	hub := signaling.NewHub(db)
	go hub.Run()

	// Handler
	handler := signaling.NewHandler(hub, cfg.JWTSecret, cfg.AllowedOrigins)

	if cfg.Environment == "production" {
		gin.SetMode(gin.ReleaseMode)
	}

	router := gin.Default()

	router.GET("/health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"status": "healthy"})
	})

	// WebSocket endpoint
	router.GET("/ws", handler.HandleWebSocket)

	log.Printf("Signaling server started on port %s", cfg.SignalingPort)

	srv := &http.Server{
		Addr:    ":" + cfg.SignalingPort,
		Handler: router,
	}

	go func() {
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("Failed to start server: %v", err)
		}
	}()

	// Graceful shutdown
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	log.Println("Shutting down signaling server...")
}
