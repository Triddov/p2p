#!/bin/bash
set -euo pipefail

set -a
source "$(dirname "${BASH_SOURCE[0]}")/../.env"
set +a

DB_CONTAINER="p2p-pg"
BACKUP_DIR="$(dirname "${BASH_SOURCE[0]}")/backups"
mkdir -p "$BACKUP_DIR"


usage() {
    echo "Usage: $0 [dump|restore] [--file <path>]"
    echo "  dump             - Create a backup"
    echo "  restore --file   - Restore from backup file"
    exit 1
}


dump() {
    local backup_file="$BACKUP_DIR/${DB_NAME}_$(date +%Y%m%d_%H%M%S).dump"

    echo "Dumping $DB_NAME from container '$DB_CONTAINER'..."
    docker exec "$DB_CONTAINER" pg_dump \
        -U "$DB_USER" \
        -d "$DB_NAME" \
        -F c > "$backup_file"

    echo "Backup saved: $backup_file"
}


restore() {
    local file="$1"

    if [[ ! -f "$file" ]]; then
        echo "Error: file not found: $file"
        exit 1
    fi

    echo "Restoring $DB_NAME into container '$DB_CONTAINER'..."
    docker exec -i "$DB_CONTAINER" pg_restore \
        -U "$DB_USER" \
        -d "$DB_NAME" \
        -c < "$file"

    echo "Restore complete"
}

case "${1:-}" in
    dump)
        dump
        ;;
    restore)
        if [[ "${2:-}" != "--file" || -z "${3:-}" ]]; then
            echo "Error: restore requires --file <path>"
            usage
        fi
        restore "$3"
        ;;
    *)
        usage
        ;;
esac
