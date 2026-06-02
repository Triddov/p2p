package user

import (
	"context"
	"database/sql"
	"fmt"

	"github.com/Triddov/p2p-server/internal/database"
	"github.com/Triddov/p2p-server/internal/models"
)

type Service struct {
	db *database.DB
}

func NewService(db *database.DB) *Service {
	return &Service{db: db}
}

// SearchUser - ищет пользователя по username
func (s *Service) SearchUser(ctx context.Context, username string) (*models.User, error) {
	var user models.User
	err := s.db.QueryRowContext(ctx,
		`SELECT id, username, identity_public_key, last_seen
         FROM users
         WHERE LOWER(username) = LOWER($1)`,
		username,
	).Scan(&user.ID, &user.Username, &user.IdentityPublicKey, &user.LastSeen)

	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("database error: %w", err)
	}

	return &user, nil
}

// GetUser получает пользователя по ID
func (s *Service) GetUser(ctx context.Context, userID string) (*models.User, error) {
	var user models.User
	err := s.db.QueryRowContext(ctx,
		`SELECT id, username, identity_public_key, last_seen
         FROM users
         WHERE id = $1`,
		userID,
	).Scan(&user.ID, &user.Username, &user.IdentityPublicKey, &user.LastSeen)

	if err != nil {
		return nil, fmt.Errorf("user not found: %w", err)
	}

	return &user, nil
}
