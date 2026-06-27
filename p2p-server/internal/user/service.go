package user

import (
	"context"
	"fmt"
	"strings"

	"github.com/Triddov/p2p-server/internal/database"
	"github.com/Triddov/p2p-server/internal/models"
)

// экранирует метасимволы LIKE (_ % \), чтобы пользовательский ввод не воспринимался как шаблон (username по схеме допускает '_')
var likeEscaper = strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`)

type Service struct {
	db *database.DB
}

func NewService(db *database.DB) *Service {
	return &Service{db: db}
}

// SearchUsers ищет пользователей по префиксу username (регистронезависимо),
// исключая запрашивающего. Возвращает не более 20 совпадений
func (s *Service) SearchUsers(ctx context.Context, prefix, excludeUserID string) ([]models.User, error) {
	pattern := likeEscaper.Replace(prefix) + "%"

	rows, err := s.db.QueryContext(ctx,
		`SELECT id, username, identity_public_key, last_seen
         FROM users
         WHERE username IS NOT NULL
           AND discoverable = TRUE
           AND lower(username) LIKE lower($1) ESCAPE '\'
           AND id <> $2
         ORDER BY username ASC
         LIMIT 20`,
		pattern, excludeUserID,
	)
	if err != nil {
		return nil, fmt.Errorf("database error: %w", err)
	}
	defer rows.Close()

	var users []models.User
	for rows.Next() {
		var u models.User
		if err := rows.Scan(&u.ID, &u.Username, &u.IdentityPublicKey, &u.LastSeen); err != nil {
			return nil, fmt.Errorf("scan error: %w", err)
		}
		users = append(users, u)
	}
	return users, rows.Err()
}

// SetDiscoverable обновляет видимость пользователя в поиске
func (s *Service) SetDiscoverable(ctx context.Context, userID string, discoverable bool) error {
	_, err := s.db.ExecContext(ctx,
		`UPDATE users SET discoverable = $1 WHERE id = $2`,
		discoverable, userID,
	)
	if err != nil {
		return fmt.Errorf("failed to update discoverable: %w", err)
	}
	return nil
}

// GetUser получает пользователя по ID
func (s *Service) GetUser(ctx context.Context, userID string) (*models.User, error) {
	var user models.User
	err := s.db.QueryRowContext(ctx,
		`SELECT id, username, identity_public_key, last_seen, online
         FROM users
         WHERE id = $1`,
		userID,
	).Scan(&user.ID, &user.Username, &user.IdentityPublicKey, &user.LastSeen, &user.Online)

	if err != nil {
		return nil, fmt.Errorf("user not found: %w", err)
	}

	return &user, nil
}
