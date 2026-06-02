package config

import (
	"fmt"
	"os"
	"strconv"

	"github.com/joho/godotenv"
)

type Config struct {
	APIPort       string
	SignalingPort string
	Environment   string

	DBHost     string
	DBPort     int
	DBUser     string
	DBPassword string
	DBName     string
	DBSSLMode  string

	RedisAddr     string
	RedisPassword string
	RedisDB       int

	JWTSecret     string
	JWTExpiration int // days

	SMTPHost          string
	SMTPPort          int
	SMTPUsername      string
	SMTPPassword      string
	SMTPFrom          string
	SMTPTLSServerName string

	// CORS
	AllowedOrigins []string
}

func Load() (*Config, error) {
	_ = godotenv.Load()

	dbPort, _ := strconv.Atoi(getEnv("DB_PORT", "5432"))
	smtpPort, _ := strconv.Atoi(getEnv("SMTP_PORT", "587"))
	redisDB, _ := strconv.Atoi(getEnv("REDIS_DB", "0"))
	jwtExpiration, _ := strconv.Atoi(getEnv("JWT_EXPIRATION_DAYS", "90"))

	return &Config{
		APIPort:       getEnv("API_PORT", "8080"),
		SignalingPort: getEnv("SIGNALING_PORT", "8082"),
		Environment:   getEnv("ENVIRONMENT", "development"),

		DBHost:     getEnv("DB_HOST", "localhost"),
		DBPort:     dbPort,
		DBUser:     getEnv("DB_USER", ""),
		DBPassword: getEnv("DB_PASSWORD", ""),
		DBName:     getEnv("DB_NAME", ""),
		DBSSLMode:  getEnv("DB_SSL_MODE", "disable"),

		RedisAddr:     getEnv("REDIS_ADDR", "localhost:6379"),
		RedisPassword: getEnv("REDIS_PASSWORD", ""),
		RedisDB:       redisDB,

		JWTSecret:     getEnv("JWT_SECRET", ""),
		JWTExpiration: jwtExpiration,

		SMTPHost:          getEnv("SMTP_HOST", ""),
		SMTPPort:          smtpPort,
		SMTPUsername:      getEnv("SMTP_USERNAME", ""),
		SMTPPassword:      getEnv("SMTP_PASSWORD", ""),
		SMTPFrom:          getEnv("SMTP_FROM", ""),
		SMTPTLSServerName: getEnv("SMTP_TLS_SERVER_NAME", ""),

		AllowedOrigins: []string{
			getEnv("ALLOWED_ORIGIN", "*"),
		},
	}, nil
}

func (c *Config) GetDSN() string {
	return fmt.Sprintf(
		"host=%s port=%d user=%s password=%s dbname=%s sslmode=%s",
		c.DBHost, c.DBPort, c.DBUser, c.DBPassword, c.DBName, c.DBSSLMode,
	)
}

func getEnv(key, defaultValue string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return defaultValue
}
