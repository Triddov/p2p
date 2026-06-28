package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gin-contrib/cors"
	"github.com/gin-gonic/gin"
	"github.com/redis/go-redis/v9"
	swaggerFiles "github.com/swaggo/files"
	ginSwagger "github.com/swaggo/gin-swagger"

	"github.com/Triddov/p2p-server/config"
	"github.com/Triddov/p2p-server/internal/auth"
	"github.com/Triddov/p2p-server/internal/database"
	"github.com/Triddov/p2p-server/internal/keys"
	"github.com/Triddov/p2p-server/internal/message"
	"github.com/Triddov/p2p-server/internal/push"
	"github.com/Triddov/p2p-server/internal/user"
	"github.com/Triddov/p2p-server/pkg/email"
)

// @title           P2P Messenger REST API
// @version         1.0
// @description     REST API peer-to-peer мессенджера: аутентификация по email-коду,
// @description     управление профилем, обмен оффлайн-сообщениями (E2EE) и распространение
// @description     Signal Protocol prekey-бандлов. Сервер не может расшифровать сообщения.
// @description     WebRTC-сигналинг вынесен в отдельный сервис и здесь не документируется.

// @contact.name    Triddov
// @contact.url     https://github.com/Triddov

// @license.name    See repository LICENCE

// @BasePath        /api

// @securityDefinitions.apikey BearerAuth
// @in                         header
// @name                       Authorization
// @description                JWT access token. Формат: "Bearer <token>".

func main() {
	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("Failed to load config: %v", err)
	}

	// Connect to Postres
	db, err := database.NewPostgres(cfg.GetDSN())
	if err != nil {
		log.Fatalf("Failed to connect to database: %v", err)
	}
	defer db.Close()

	log.Println("Connected to PostgreSQL")

	// Connect to Redis
	rdb := redis.NewClient(&redis.Options{
		Addr:     cfg.RedisAddr,
		Password: cfg.RedisPassword,
		DB:       cfg.RedisDB,
	})
	defer rdb.Close()

	if err := rdb.Ping(context.Background()).Err(); err != nil {
		log.Fatalf("Failed to connect to Redis: %v", err)
	}

	log.Println("Connected to Redis")

	// Email sender
	emailSender := email.NewSender(
		cfg.SMTPHost,
		cfg.SMTPPort,
		cfg.SMTPUsername,
		cfg.SMTPPassword,
		cfg.SMTPFrom,
		cfg.SMTPTLSServerName,
	)

	// Services
	authService := auth.NewService(db, rdb, emailSender, cfg.JWTSecret, cfg.JWTExpiration)
	userService := user.NewService(db)

	var notifier message.Notifier
	if cfg.FirebaseCredentials != "" {
		n, err := push.NewNotifier(context.Background(), cfg.FirebaseCredentials, userService)
		if err != nil {
			log.Printf("Push disabled: failed to init Firebase: %v", err)
		} else {
			notifier = n
			log.Println("Push (FCM) enabled")
		}
	}

	messageService := message.NewService(db, notifier)
	keysService := keys.NewService(db)

	// Handlers
	authHandler := auth.NewHandler(authService)
	userHandler := user.NewHandler(userService)
	messageHandler := message.NewHandler(messageService)
	keysHandler := keys.NewHandler(keysService)

	if cfg.Environment == "production" {
		gin.SetMode(gin.ReleaseMode)
	}

	router := gin.Default()

	router.Use(cors.New(cors.Config{
		AllowOrigins:     cfg.AllowedOrigins,
		AllowMethods:     []string{"GET", "POST", "PUT", "DELETE", "OPTIONS"},
		AllowHeaders:     []string{"Origin", "Content-Type", "Authorization"},
		ExposeHeaders:    []string{"Content-Length"},
		AllowCredentials: true,
		MaxAge:           12 * time.Hour,
	}))

	router.GET("/health", func(c *gin.Context) {
		if err := db.HealthCheck(); err != nil {
			c.JSON(http.StatusServiceUnavailable, gin.H{"status": "unhealthy"})
			return
		}
		c.JSON(http.StatusOK, gin.H{"status": "healthy"})
	})

	// Swagger UI - только вне production (документация API не публикуется в проде)
	if cfg.Environment != "production" {
		router.GET("/swagger/*any", ginSwagger.WrapHandler(swaggerFiles.Handler))
		log.Println("Swagger UI enabled at /swagger/index.html")
	}

	// Public routes
	api := router.Group("/api")
	{
		// Auth
		api.POST("/auth/request-code", authHandler.RequestCode)
		api.POST("/auth/verify", authHandler.VerifyCode)
		api.POST("/auth/refresh", authHandler.RefreshToken)
	}

	// Protected routes
	protected := api.Group("")
	protected.Use(auth.JWTAuthMiddleware(cfg.JWTSecret))
	{
		// User
		protected.POST("/users/set-username", authHandler.SetUsername)
		protected.PUT("/users/discoverable", userHandler.SetDiscoverable)
		protected.PUT("/users/fcm-token", userHandler.RegisterFcmToken)
		protected.DELETE("/users/fcm-token", userHandler.DeleteFcmToken)
		protected.GET("/users/search", userHandler.SearchUser)
		protected.GET("/users/:userId", userHandler.GetUser)

		// Messages
		protected.POST("/messages/store", messageHandler.StoreMessage)
		protected.GET("/messages/pending", messageHandler.GetPendingMessages)
		protected.POST("/messages/ack", messageHandler.AckMessages)

		// Signal Protocol keys
		protected.PUT("/keys/prekeys", keysHandler.RegisterPrekeys)
		protected.PUT("/keys/signed-prekey", keysHandler.UpdateSignedPrekey)
		protected.POST("/keys/otks", keysHandler.AddOneTimePrekeys)
		protected.GET("/keys/:userId", keysHandler.GetPrekeyBundle)
		protected.GET("/keys/count", keysHandler.GetOTKCount)
	}

	srv := &http.Server{
		Addr:    ":" + cfg.APIPort,
		Handler: router,
	}

	go func() {
		log.Printf("REST API server started on port %s", cfg.APIPort)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("Failed to start server: %v", err)
		}
	}()

	// Graceful shutdown
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	log.Println("Shutting down server...")

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := srv.Shutdown(ctx); err != nil {
		log.Fatalf("Server forced to shutdown: %v", err)
	}

	log.Println("Server stopped")
}
