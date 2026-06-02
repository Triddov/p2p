package auth

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/base64"
	"fmt"
	"math/big"
	"strings"
	"time"

	"github.com/Triddov/p2p-server/internal/database"
	"github.com/Triddov/p2p-server/internal/models"
	"github.com/Triddov/p2p-server/pkg/email"
	jwtpkg "github.com/Triddov/p2p-server/pkg/jwt"
	"github.com/google/uuid"
	"github.com/redis/go-redis/v9"
)

const refreshTokenTTL = 180 * 24 * time.Hour

type Service struct {
	db          *database.DB
	redis       *redis.Client
	emailSender *email.Sender
	jwtSecret   string
	jwtExpDays  int
}

func NewService(db *database.DB, rdb *redis.Client, emailSender *email.Sender, jwtSecret string, jwtExpDays int) *Service {
	return &Service{
		db:          db,
		redis:       rdb,
		emailSender: emailSender,
		jwtSecret:   jwtSecret,
		jwtExpDays:  jwtExpDays,
	}
}

// RequestVerificationCode отправляет код на email
func (s *Service) RequestVerificationCode(ctx context.Context, email, deviceID string) error {
	// Rate limiting
	key := fmt.Sprintf("email_attempts:%s", email)
	attempts, err := s.redis.Incr(ctx, key).Result()
	if err != nil {
		return fmt.Errorf("redis error: %w", err)
	}

	if attempts == 1 {
		s.redis.Expire(ctx, key, time.Hour)
	}

	if attempts > 10 {
		return fmt.Errorf("too many attempts, try again in 1 hour")
	}

	code := generateVerificationCode()

	codeKey := fmt.Sprintf("sms_code:%s", email)
	err = s.redis.Set(ctx, codeKey, code, 5*time.Minute).Err()
	if err != nil {
		return fmt.Errorf("failed to save code: %w", err)
	}

	if err := s.emailSender.SendVerificationCode(email, code); err != nil {
		return fmt.Errorf("failed to send email: %w", err)
	}

	return nil
}

// VerifyCodeAndRegister верифицирует код и создаёт/обновляет пользователя.
// Возвращает пользователя, access token и refresh token.
func (s *Service) VerifyCodeAndRegister(ctx context.Context, email, code, deviceID, publicKeyBase64 string) (*models.User, string, string, error) {
	codeKey := fmt.Sprintf("sms_code:%s", email)
	storedCode, err := s.redis.Get(ctx, codeKey).Result()
	if err == redis.Nil {
		return nil, "", "", fmt.Errorf("code expired or not found")
	}
	if err != nil {
		return nil, "", "", fmt.Errorf("redis error: %w", err)
	}

	if storedCode != code {
		return nil, "", "", fmt.Errorf("invalid code")
	}

	s.redis.Del(ctx, codeKey)

	publicKey, err := base64.StdEncoding.DecodeString(publicKeyBase64)
	if err != nil {
		return nil, "", "", fmt.Errorf("invalid public key format: %w", err)
	}

	var user models.User
	err = s.db.QueryRowContext(ctx,
		"SELECT id, email, username, identity_public_key, created_at FROM users WHERE email = $1",
		email,
	).Scan(&user.ID, &user.Email, &user.Username, &user.IdentityPublicKey, &user.CreatedAt)

	if err == sql.ErrNoRows {
		userID := uuid.New().String()

		_, err = s.db.ExecContext(ctx,
			`INSERT INTO users (id, email, identity_public_key, device_id)
             VALUES ($1, $2, $3, $4)`,
			userID, email, publicKey, deviceID,
		)
		if err != nil {
			return nil, "", "", fmt.Errorf("failed to create user: %w", err)
		}

		user = models.User{
			ID:                userID,
			Email:             email,
			IdentityPublicKey: publicKey,
			CreatedAt:         time.Now(),
		}
	} else if err != nil {
		return nil, "", "", fmt.Errorf("database error: %w", err)
	} else {
		_, err = s.db.ExecContext(ctx,
			`UPDATE users
             SET identity_public_key = $1, device_id = $2, last_seen = NOW()
             WHERE id = $3`,
			publicKey, deviceID, user.ID,
		)
		if err != nil {
			return nil, "", "", fmt.Errorf("failed to update user: %w", err)
		}
	}

	accessToken, err := jwtpkg.GenerateToken(user.ID, email, s.jwtSecret, s.jwtExpDays)
	if err != nil {
		return nil, "", "", fmt.Errorf("failed to generate token: %w", err)
	}

	refreshToken, err := s.storeRefreshToken(ctx, user.ID, email)
	if err != nil {
		return nil, "", "", fmt.Errorf("failed to store refresh token: %w", err)
	}

	return &user, accessToken, refreshToken, nil
}

// RefreshToken проверяет refresh token, ротирует его и возвращает новую пару токенов.
func (s *Service) RefreshToken(ctx context.Context, refreshToken string) (string, string, error) {
	key := fmt.Sprintf("refresh_token:%s", refreshToken)
	payload, err := s.redis.Get(ctx, key).Result()
	if err == redis.Nil {
		return "", "", fmt.Errorf("refresh token expired or not found")
	}
	if err != nil {
		return "", "", fmt.Errorf("redis error: %w", err)
	}

	parts := strings.SplitN(payload, ":", 2)
	if len(parts) != 2 {
		return "", "", fmt.Errorf("invalid refresh token")
	}
	userID, email := parts[0], parts[1]

	// Ротация: удаление страрого токена!
	s.redis.Del(ctx, key)

	newAccessToken, err := jwtpkg.GenerateToken(userID, email, s.jwtSecret, s.jwtExpDays)
	if err != nil {
		return "", "", fmt.Errorf("failed to generate token: %w", err)
	}

	newRefreshToken, err := s.storeRefreshToken(ctx, userID, email)
	if err != nil {
		return "", "", fmt.Errorf("failed to store refresh token: %w", err)
	}

	return newAccessToken, newRefreshToken, nil
}

// SetUsername устанавливает username для пользователя
func (s *Service) SetUsername(ctx context.Context, userID, username string) error {
	_, err := s.db.ExecContext(ctx,
		"UPDATE users SET username = $1 WHERE id = $2",
		username, userID,
	)
	if err != nil {
		return fmt.Errorf("failed to set username: %w", err)
	}
	return nil
}

func (s *Service) storeRefreshToken(ctx context.Context, userID, email string) (string, error) {
	token := uuid.New().String()
	key := fmt.Sprintf("refresh_token:%s", token)
	payload := fmt.Sprintf("%s:%s", userID, email)

	if err := s.redis.Set(ctx, key, payload, refreshTokenTTL).Err(); err != nil {
		return "", err
	}
	return token, nil
}

func generateVerificationCode() string {
	max := big.NewInt(1000000)
	n, _ := rand.Int(rand.Reader, max)
	return fmt.Sprintf("%06d", n.Int64())
}
