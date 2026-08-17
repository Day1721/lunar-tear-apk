package mobile

// This file mirrors cmd/lunar-tear/{main,grpc,admin}.go, which live in package
// main upstream and are therefore unimportable. Everything else comes straight
// from lunar-tear/server/internal/...
//
// The registerServices body below is copied verbatim from
// cmd/lunar-tear/grpc.go. drift_test.go compares the two sets of registrations
// and fails the build if upstream adds a service we have not mirrored — without
// it, a missing service degrades silently into interceptor.UnknownService.

import (
	"context"
	"crypto/subtle"
	"fmt"
	"log"
	"net"
	"net/http"
	"strconv"

	pb "lunar-tear/server/gen/proto"
	"lunar-tear/server/internal/interceptor"
	"lunar-tear/server/internal/runtime"
	"lunar-tear/server/internal/service"
	"lunar-tear/server/internal/store"

	"google.golang.org/grpc"
	"google.golang.org/grpc/reflection"
)

// userStoreIface is the anonymous interface upstream passes around
// (cmd/lunar-tear/grpc.go:37-40), named here so it can be reused.
type userStoreIface interface {
	store.UserRepository
	store.SessionRepository
}

// loggingListener is copied from cmd/lunar-tear/grpc.go:18-30.
type loggingListener struct {
	net.Listener
}

func (l loggingListener) Accept() (net.Conn, error) {
	conn, err := l.Listener.Accept()
	if err != nil {
		log.Printf("[gRPC] Accept error: %v", err)
		return nil, err
	}
	log.Printf("[gRPC] New connection from %v", conn.RemoteAddr())
	return conn, nil
}

// startGRPC mirrors cmd/lunar-tear/grpc.go:32-73, returning an error where
// upstream calls log.Fatalf on bind failure.
func startGRPC(cfg *config, userStore userStoreIface, holder *runtime.Holder) (*grpc.Server, error) {
	lis, err := net.Listen("tcp", cfg.GRPCListen)
	if err != nil {
		return nil, fmt.Errorf("listen on %s: %w", cfg.GRPCListen, err)
	}
	lis = loggingListener{Listener: lis}

	diffInterceptor := interceptor.NewDiffInterceptor(userStore, userStore)
	grpcServer := grpc.NewServer(
		grpc.ChainUnaryInterceptor(interceptor.Platform, interceptor.Logging, diffInterceptor, interceptor.TimeSync),
		grpc.UnknownServiceHandler(interceptor.UnknownService),
	)

	registerServices(grpcServer, cfg.GRPCPublicAddr, cfg.octoURL(), cfg.AuthURL, userStore, holder, cfg.NoRegister)

	reflection.Register(grpcServer)

	log.Printf("gRPC server listening on %s", lis.Addr())
	log.Printf("public address: %s", cfg.GRPCPublicAddr)

	if cfg.NoRegister {
		log.Print("[!!WARNING!!] The gRPC server is running in NO-REGISTER mode. All new user registrations are denied, only existing accounts and auth-server logins are permitted.")
	}

	go func() {
		if err := grpcServer.Serve(lis); err != nil {
			log.Printf("gRPC server stopped: %v", err)
		}
	}()
	return grpcServer, nil
}

// startAdmin mirrors cmd/lunar-tear/admin.go, with two deliberate changes: the
// bearer token comes from config instead of the LUNAR_ADMIN_TOKEN environment
// variable, and the *http.Server is returned so Stop() can shut it down
// (upstream uses a bare http.ListenAndServe and cannot).
//
// Fail-closed semantics are preserved: no token means the listener never binds.
func startAdmin(cfg *config, holder *runtime.Holder) *http.Server {
	if cfg.AdminToken == "" {
		log.Println("[admin] disabled (no admin token set)")
		return nil
	}
	expected := []byte("Bearer " + cfg.AdminToken)

	mux := http.NewServeMux()
	mux.HandleFunc("/api/admin/master-data/reload", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		got := []byte(r.Header.Get("Authorization"))
		if len(got) != len(expected) || subtle.ConstantTimeCompare(got, expected) != 1 {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		if err := holder.Reload(); err != nil {
			log.Printf("[admin] master-data reload failed: %v", err)
			http.Error(w, "master-data reload failed", http.StatusInternalServerError)
			return
		}
		log.Printf("[admin] master-data reloaded by %s", r.RemoteAddr)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"ok":true}`))
	})

	srv := &http.Server{Addr: cfg.AdminListen, Handler: mux}
	log.Printf("[admin] webhook listener on %s (token-gated)", cfg.AdminListen)
	go func() {
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Printf("[admin] webhook listener failed: %v", err)
		}
	}()
	return srv
}

func shutdownAdmin(srv *http.Server) {
	if srv != nil {
		_ = srv.Shutdown(context.Background())
	}
}

// registerServices is copied verbatim from cmd/lunar-tear/grpc.go:75-128.
// Guarded by drift_test.go — do not edit by hand; re-splice from upstream.
func registerServices(
	srv *grpc.Server,
	publicAddr string,
	octoURL string,
	authURL string,
	userStore interface {
		store.UserRepository
		store.SessionRepository
	},
	holder *runtime.Holder,
	noRegister bool,
) {
	pubHost, pubPortStr, _ := net.SplitHostPort(publicAddr)
	pubPort, _ := strconv.Atoi(pubPortStr)

	pb.RegisterBannerServiceServer(srv, service.NewBannerServiceServer(holder))
	pb.RegisterUserServiceServer(srv, service.NewUserServiceServer(userStore, userStore, holder, authURL, noRegister))
	pb.RegisterBattleServiceServer(srv, service.NewBattleServiceServer(userStore, userStore))
	pb.RegisterConfigServiceServer(srv, service.NewConfigServiceServer(pubHost, int32(pubPort), octoURL))
	pb.RegisterDataServiceServer(srv, service.NewDataServiceServer(userStore, userStore))
	pb.RegisterTutorialServiceServer(srv, service.NewTutorialServiceServer(userStore, userStore, holder))
	pb.RegisterGachaServiceServer(srv, service.NewGachaServiceServer(userStore, userStore, holder))
	pb.RegisterGiftServiceServer(srv, service.NewGiftServiceServer(userStore, userStore))
	pb.RegisterGamePlayServiceServer(srv, service.NewGameplayServiceServer())
	pb.RegisterGimmickServiceServer(srv, service.NewGimmickServiceServer(userStore, userStore, holder))
	pb.RegisterQuestServiceServer(srv, service.NewQuestServiceServer(userStore, userStore, holder))
	pb.RegisterNotificationServiceServer(srv, service.NewNotificationServiceServer(userStore, userStore))
	pb.RegisterCageOrnamentServiceServer(srv, service.NewCageOrnamentServiceServer(userStore, userStore, holder))
	pb.RegisterDeckServiceServer(srv, service.NewDeckServiceServer(userStore, userStore))
	pb.RegisterFriendServiceServer(srv, service.NewFriendServiceServer(userStore, userStore))
	pb.RegisterLoginBonusServiceServer(srv, service.NewLoginBonusServiceServer(userStore, userStore, holder))
	pb.RegisterNaviCutInServiceServer(srv, service.NewNaviCutInServiceServer(userStore, userStore))
	pb.RegisterContentsStoryServiceServer(srv, service.NewContentsStoryServiceServer(userStore, userStore))
	pb.RegisterDokanServiceServer(srv, service.NewDokanServiceServer(userStore, userStore))
	pb.RegisterPortalCageServiceServer(srv, service.NewPortalCageServiceServer(userStore, userStore))
	pb.RegisterCharacterViewerServiceServer(srv, service.NewCharacterViewerServiceServer(userStore, userStore, holder))
	pb.RegisterMissionServiceServer(srv, service.NewMissionServiceServer(userStore, userStore))
	pb.RegisterShopServiceServer(srv, service.NewShopServiceServer(userStore, userStore, holder))
	pb.RegisterCostumeServiceServer(srv, service.NewCostumeServiceServer(userStore, userStore, holder))
	pb.RegisterMovieServiceServer(srv, service.NewMovieServiceServer(userStore, userStore))
	pb.RegisterOmikujiServiceServer(srv, service.NewOmikujiServiceServer(userStore, userStore, holder))
	pb.RegisterWeaponServiceServer(srv, service.NewWeaponServiceServer(userStore, userStore, holder))
	pb.RegisterExploreServiceServer(srv, service.NewExploreServiceServer(userStore, userStore, holder))
	pb.RegisterCharacterBoardServiceServer(srv, service.NewCharacterBoardServiceServer(userStore, userStore, holder))
	pb.RegisterPartsServiceServer(srv, service.NewPartsServiceServer(userStore, userStore, holder))
	pb.RegisterCharacterServiceServer(srv, service.NewCharacterServiceServer(userStore, userStore, holder))
	pb.RegisterCompanionServiceServer(srv, service.NewCompanionServiceServer(userStore, userStore, holder))
	pb.RegisterMaterialServiceServer(srv, service.NewMaterialServiceServer(userStore, userStore, holder))
	pb.RegisterConsumableItemServiceServer(srv, service.NewConsumableItemServiceServer(userStore, userStore, holder))
	pb.RegisterSideStoryQuestServiceServer(srv, service.NewSideStoryQuestServiceServer(userStore, userStore, holder))
	pb.RegisterBigHuntServiceServer(srv, service.NewBigHuntServiceServer(userStore, userStore, holder))
	pb.RegisterRewardServiceServer(srv, service.NewRewardServiceServer(userStore, userStore, holder))
	pb.RegisterLabyrinthServiceServer(srv, service.NewLabyrinthServiceServer(userStore, userStore, holder))
}
