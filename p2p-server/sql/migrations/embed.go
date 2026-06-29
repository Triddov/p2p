// Package migrations embeds the SQL migration files into the binary so they
// can be applied on startup without shipping the files separately.
package migrations

import "embed"

// Files holds every *.sql migration in this directory.
//
//go:embed *.sql
var Files embed.FS
