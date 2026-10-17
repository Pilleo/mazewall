//go:build linux

package admissionprobe

import (
	"errors"
	"fmt"
	"io"
	"os"

	"golang.org/x/sys/unix"
)

// DigestFile qualifies AF_ALG for an externally process-timed developer probe.
// It provides no file-I/O deadline or production asset-admission validation.
func DigestFile(path string, maximum int64) (digest [32]byte, failure error) {
	if maximum < 0 || maximum > 512<<20 {
		return digest, fmt.Errorf("invalid hash byte budget")
	}
	algorithm, err := unix.Socket(unix.AF_ALG, unix.SOCK_SEQPACKET|unix.SOCK_CLOEXEC, 0)
	if err != nil {
		return digest, err
	}
	defer func() { failure = errors.Join(failure, unix.Close(algorithm)) }()
	if err := unix.Bind(algorithm, &unix.SockaddrALG{Type: "hash", Name: "sha256"}); err != nil {
		return digest, err
	}
	// AF_ALG has no peer address; x/sys's Accept4 requests one and fails.
	accepted, _, errno := unix.Syscall6(unix.SYS_ACCEPT4, uintptr(algorithm), 0, 0, unix.SOCK_CLOEXEC, 0, 0)
	if errno != 0 {
		return digest, errno
	}
	operation := int(accepted)
	defer func() { failure = errors.Join(failure, unix.Close(operation)) }()
	file, err := os.Open(path)
	if err != nil {
		return digest, err
	}
	defer func() { failure = errors.Join(failure, file.Close()) }()
	buffer := make([]byte, 65536)
	var total int64
	for {
		n, readErr := file.Read(buffer)
		if readErr != nil && readErr != io.EOF {
			return digest, readErr
		}
		if int64(n) > maximum-total {
			return digest, fmt.Errorf("hash byte limit")
		}
		total += int64(n)
		for sent := 0; sent < n; {
			count, err := unix.SendmsgN(operation, buffer[sent:n], nil, nil, unix.MSG_MORE)
			if err != nil {
				return digest, err
			}
			if count <= 0 || count > n-sent {
				return digest, io.ErrShortWrite
			}
			sent += count
		}
		if readErr == io.EOF {
			break
		}
	}
	n, err := unix.SendmsgN(operation, nil, nil, nil, 0)
	if err != nil {
		return digest, err
	}
	if n != 0 {
		return digest, io.ErrShortWrite
	}
	n, _, err = unix.Recvfrom(operation, digest[:], 0)
	if err != nil {
		return digest, err
	}
	if n != len(digest) {
		return digest, io.ErrUnexpectedEOF
	}
	return digest, nil
}
