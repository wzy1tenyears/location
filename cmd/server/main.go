package main

import (
	"context"
	"errors"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"familylocation/location-v3/internal/app"
	"familylocation/location-v3/internal/config"
	"familylocation/location-v3/internal/database"
	"familylocation/location-v3/internal/repositories"
)

const (
	appChallengeCleanupInterval  = time.Minute
	appChallengeCleanupBatch     = 500
	appChallengeCleanupBatches   = 10
	locationShareCleanupInterval = time.Hour
	locationShareCleanupBatch    = 500
	locationShareCleanupBatches  = 10
	supportTicketCleanupInterval = time.Hour
	supportTicketCleanupBatch    = 100
	supportTicketCleanupBatches  = 10
	supportTicketRetention       = 180 * 24 * time.Hour
)

func main() {
	cfg := config.Load()
	if err := config.Validate(cfg); err != nil {
		log.Fatalf("invalid configuration: %v", err)
	}

	db, err := database.Open(cfg.Database)
	if err != nil {
		log.Fatalf("open database: %v", err)
	}
	defer db.Close()
	maintenanceCtx, stopMaintenance := context.WithCancel(context.Background())
	defer stopMaintenance()
	go runAppChallengeCleanup(maintenanceCtx, repositories.NewAppChallengeRepository(db))
	go runLocationShareCleanup(maintenanceCtx, repositories.NewLocationShareRepository(db))
	go runSupportTicketCleanup(maintenanceCtx, repositories.NewSupportTicketMaintenanceRepository(db))

	router := app.NewRouter(cfg, db)
	server := &http.Server{
		Addr:              cfg.Server.Addr,
		Handler:           router,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       20 * time.Second,
		WriteTimeout:      20 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	errCh := make(chan error, 1)
	go func() {
		log.Printf("family-location go backend listening on %s", cfg.Server.Addr)
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- err
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)

	select {
	case err := <-errCh:
		log.Fatalf("server failed: %v", err)
	case <-stop:
	}
	stopMaintenance()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := server.Shutdown(ctx); err != nil {
		log.Printf("server shutdown failed: %v", err)
	}
}

func runAppChallengeCleanup(ctx context.Context, repo repositories.AppChallengeRepository) {
	cleanup := func() {
		_, err := repo.DeleteExpiredBatches(ctx, time.Now(), appChallengeCleanupBatch, appChallengeCleanupBatches)
		if err != nil && !errors.Is(err, context.Canceled) {
			log.Printf("app challenge cleanup failed: %v", err)
		}
	}
	cleanup()
	ticker := time.NewTicker(appChallengeCleanupInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			cleanup()
		}
	}
}

func runLocationShareCleanup(ctx context.Context, repo repositories.LocationShareRepository) {
	cleanup := func() {
		_, err := repo.DeleteExpiredBatches(ctx, time.Now(), locationShareCleanupBatch, locationShareCleanupBatches)
		if err != nil && !errors.Is(err, context.Canceled) {
			log.Printf("location share cleanup failed: %v", err)
		}
	}
	cleanup()
	ticker := time.NewTicker(locationShareCleanupInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			cleanup()
		}
	}
}

func runSupportTicketCleanup(ctx context.Context, repo repositories.SupportTicketMaintenanceRepository) {
	cleanup := func() {
		cutoff := time.Now().Add(-supportTicketRetention)
		_, err := repo.DeleteClosedBatches(ctx, cutoff, supportTicketCleanupBatch, supportTicketCleanupBatches)
		if err != nil && !errors.Is(err, context.Canceled) {
			log.Printf("support ticket cleanup failed: %v", err)
		}
	}
	cleanup()
	ticker := time.NewTicker(supportTicketCleanupInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			cleanup()
		}
	}
}
