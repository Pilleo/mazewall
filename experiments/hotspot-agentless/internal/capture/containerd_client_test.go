package capture

import (
	"context"
	"net"
	"path/filepath"
	"testing"

	containersapi "github.com/containerd/containerd/api/services/containers/v1"
	"github.com/containerd/errdefs"
	containerdclient "github.com/inspektor-gadget/inspektor-gadget/pkg/container-utils/containerd"
	containerutils "github.com/inspektor-gadget/inspektor-gadget/pkg/container-utils/types"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

type containerdListServer struct {
	containersapi.UnimplementedContainersServer
	namespace string
	denied    bool
}

func (s *containerdListServer) List(ctx context.Context, _ *containersapi.ListContainersRequest) (*containersapi.ListContainersResponse, error) {
	md, _ := metadata.FromIncomingContext(ctx)
	namespace := md.Get("containerd-namespace")
	if len(namespace) != 1 || namespace[0] != s.namespace {
		return nil, status.Errorf(codes.InvalidArgument, "unexpected namespace: %v", namespace)
	}
	if s.denied {
		return nil, status.Error(codes.PermissionDenied, "fixture denied discovery")
	}
	return &containersapi.ListContainersResponse{}, nil
}

func TestContainerdClientRPC(t *testing.T) {
	for _, tc := range []struct {
		name, protocol, namespace string
		denied                    bool
	}{
		{name: "default", namespace: "k8s.io"},
		{name: "custom", protocol: "internal", namespace: "tenant-a"},
		{name: "denied", protocol: "internal", namespace: "k8s.io", denied: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			socket := filepath.Join(t.TempDir(), "s")
			listener, err := net.Listen("unix", socket)
			if err != nil {
				t.Fatal(err)
			}
			server := grpc.NewServer()
			containersapi.RegisterContainersServer(server, &containerdListServer{namespace: tc.namespace, denied: tc.denied})
			done := make(chan error, 1)
			go func() { done <- server.Serve(listener) }()
			t.Cleanup(func() {
				server.Stop()
				_ = listener.Close()
				if err := <-done; err != nil && err != grpc.ErrServerStopped {
					t.Error(err)
				}
			})
			var config *containerutils.ExtraConfig
			if tc.name == "custom" {
				config = &containerutils.ExtraConfig{Namespace: tc.namespace}
			}
			client, err := containerdclient.NewContainerdClient(socket, tc.protocol, config)
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() {
				if err := client.Close(); err != nil {
					t.Error(err)
				}
			})
			containers, err := client.GetContainers()
			if tc.denied {
				if !errdefs.IsPermissionDenied(err) {
					t.Fatalf("discovery failure lost: %v", err)
				}
			} else if err != nil || len(containers) != 0 {
				t.Fatalf("namespace-preserving discovery: %v %v", containers, err)
			}
		})
	}
}
