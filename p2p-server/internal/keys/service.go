package keys

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

// RegisterPrekeys сохраняет registration ID, signed prekey и пакет OTK для пользователя.
// Signed prekey заменяется при повторном вызове (ротация). OTK добавляются в пул
func (s *Service) RegisterPrekeys(
	ctx context.Context,
	userID string,
	registrationID int,
	identityKey, signedPrekeyPub, signedPrekeySig []byte,
	signedPrekeyID int,
	oneTimePrekeys []models.OneTimePrekey,
) error {
	_, err := s.db.ExecContext(ctx,
		"UPDATE users SET registration_id = $1, identity_public_key = $2 WHERE id = $3",
		registrationID, identityKey, userID,
	)
	if err != nil {
		return fmt.Errorf("failed to update registration: %w", err)
	}

	_, err = s.db.ExecContext(ctx,
		`INSERT INTO signed_prekeys (user_id, prekey_id, public_key, signature)
		 VALUES ($1, $2, $3, $4)
		 ON CONFLICT (user_id) DO UPDATE
		 SET prekey_id = EXCLUDED.prekey_id,
		     public_key = EXCLUDED.public_key,
		     signature = EXCLUDED.signature,
		     created_at = NOW()`,
		userID, signedPrekeyID, signedPrekeyPub, signedPrekeySig,
	)
	if err != nil {
		return fmt.Errorf("failed to store signed prekey: %w", err)
	}

	for _, otk := range oneTimePrekeys {
		_, err = s.db.ExecContext(ctx,
			`INSERT INTO one_time_prekeys (user_id, prekey_id, public_key)
			 VALUES ($1, $2, $3)
			 ON CONFLICT (user_id, prekey_id) DO NOTHING`,
			userID, otk.PrekeyID, otk.PublicKey,
		)
		if err != nil {
			return fmt.Errorf("failed to store prekey %d: %w", otk.PrekeyID, err)
		}
	}

	return nil
}

// UpdateSignedPrekey заменяет signed prekey пользователя (периодическая ротация)
func (s *Service) UpdateSignedPrekey(ctx context.Context, userID string, prekeyID int, pub, sig []byte) error {
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO signed_prekeys (user_id, prekey_id, public_key, signature)
		 VALUES ($1, $2, $3, $4)
		 ON CONFLICT (user_id) DO UPDATE
		 SET prekey_id = EXCLUDED.prekey_id,
		     public_key = EXCLUDED.public_key,
		     signature = EXCLUDED.signature,
		     created_at = NOW()`,
		userID, prekeyID, pub, sig,
	)
	if err != nil {
		return fmt.Errorf("failed to update signed prekey: %w", err)
	}
	return nil
}

// AddOneTimePrekeys дозаливает пул one-time prekeys (пополнение клиентом
func (s *Service) AddOneTimePrekeys(ctx context.Context, userID string, oneTimePrekeys []models.OneTimePrekey) error {
	for _, otk := range oneTimePrekeys {
		_, err := s.db.ExecContext(ctx,
			`INSERT INTO one_time_prekeys (user_id, prekey_id, public_key)
			 VALUES ($1, $2, $3)
			 ON CONFLICT (user_id, prekey_id) DO NOTHING`,
			userID, otk.PrekeyID, otk.PublicKey,
		)
		if err != nil {
			return fmt.Errorf("failed to store prekey %d: %w", otk.PrekeyID, err)
		}
	}
	return nil
}

// GetPrekeyBundle формирует prekey bundle для установки X3DH сессии.
// Атомарно изымает один OTK из пула (если есть). Без OTK тож валидно по спецификации
func (s *Service) GetPrekeyBundle(ctx context.Context, userID string) (*models.PrekeyBundle, error) {
	var bundle models.PrekeyBundle
	var regID sql.NullInt64

	err := s.db.QueryRowContext(ctx,
		"SELECT identity_public_key, registration_id FROM users WHERE id = $1",
		userID,
	).Scan(&bundle.IdentityKey, &regID)
	if err == sql.ErrNoRows {
		return nil, fmt.Errorf("user not found")
	}
	if err != nil {
		return nil, fmt.Errorf("database error: %w", err)
	}
	if !regID.Valid {
		return nil, fmt.Errorf("user has not uploaded prekeys yet")
	}
	bundle.RegistrationID = int(regID.Int64)

	err = s.db.QueryRowContext(ctx,
		"SELECT prekey_id, public_key, signature FROM signed_prekeys WHERE user_id = $1",
		userID,
	).Scan(&bundle.SignedPrekeyID, &bundle.SignedPrekey, &bundle.SignedPrekeySig)
	if err == sql.ErrNoRows {
		return nil, fmt.Errorf("user has no signed prekey")
	}
	if err != nil {
		return nil, fmt.Errorf("database error: %w", err)
	}

	// Атомарное изъятие OTK из пула
	var otkPrekeyID int
	var otkPublicKey []byte
	err = s.db.QueryRowContext(ctx,
		`DELETE FROM one_time_prekeys
		 WHERE id = (
		     SELECT id FROM one_time_prekeys
		     WHERE user_id = $1
		     ORDER BY prekey_id ASC
		     LIMIT 1
		     FOR UPDATE SKIP LOCKED
		 )
		 RETURNING prekey_id, public_key`,
		userID,
	).Scan(&otkPrekeyID, &otkPublicKey)
	if err == nil {
		bundle.OneTimePrekeyID = &otkPrekeyID
		bundle.OneTimePrekey = otkPublicKey
	}
	// sql.ErrNoRows = OTK pool exhausted; bundle without OTK is valid per spec.

	return &bundle, nil
}

// GetOTKCount возвращает количество оставшихся OTK для пользователя.
// Клиент использует это чтобы понять, когда нужно пополнить пул
func (s *Service) GetOTKCount(ctx context.Context, userID string) (int, error) {
	var count int
	err := s.db.QueryRowContext(ctx,
		"SELECT COUNT(*) FROM one_time_prekeys WHERE user_id = $1",
		userID,
	).Scan(&count)
	if err != nil {
		return 0, fmt.Errorf("database error: %w", err)
	}
	return count, nil
}
