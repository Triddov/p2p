package database

import (
	"errors"
	"fmt"

	"github.com/golang-migrate/migrate/v4"
	migratepg "github.com/golang-migrate/migrate/v4/database/postgres"
	"github.com/golang-migrate/migrate/v4/source/iofs"

	"github.com/Triddov/p2p-server/sql/migrations"
)

// RunMigrations applies all pending schema migrations embedded in the binary.
// It reuses the existing connection pool and is guarded by a Postgres advisory
// lock (handled by the migrate driver), so concurrent instances are safe.
// Idempotent: a no-op when the schema is already up to date.
func (db *DB) RunMigrations() error {
	source, err := iofs.New(migrations.Files, ".")
	if err != nil {
		return fmt.Errorf("migrations: load embedded source: %w", err)
	}

	driver, err := migratepg.WithInstance(db.DB, &migratepg.Config{})
	if err != nil {
		return fmt.Errorf("migrations: init postgres driver: %w", err)
	}

	m, err := migrate.NewWithInstance("iofs", source, "postgres", driver)
	if err != nil {
		return fmt.Errorf("migrations: init migrator: %w", err)
	}

	if err := m.Up(); err != nil && !errors.Is(err, migrate.ErrNoChange) {
		return fmt.Errorf("migrations: apply: %w", err)
	}
	return nil
}
