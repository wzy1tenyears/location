package repositories

import (
	"context"
	"database/sql"
)

type GeoRepository struct {
	db *sql.DB
}

type GeoRegion struct {
	Level int
	Name  string
}

func NewGeoRepository(db *sql.DB) GeoRepository {
	return GeoRepository{db: db}
}

func (repo GeoRepository) ChinaRegions(ctx context.Context) ([]GeoRegion, error) {
	exists, err := repo.tableExists(ctx, "china_regions")
	if err != nil || !exists {
		return nil, err
	}

	rows, err := repo.db.QueryContext(ctx, "SELECT level, name FROM china_regions ORDER BY level ASC, id ASC")
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	regions := make([]GeoRegion, 0)
	for rows.Next() {
		var region GeoRegion
		if err := rows.Scan(&region.Level, &region.Name); err != nil {
			return nil, err
		}
		regions = append(regions, region)
	}
	return regions, rows.Err()
}

func (repo GeoRepository) tableExists(ctx context.Context, table string) (bool, error) {
	var count int
	err := repo.db.QueryRowContext(ctx, `
SELECT COUNT(*)
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name = ?
LIMIT 1`, table).Scan(&count)
	return count > 0, err
}
