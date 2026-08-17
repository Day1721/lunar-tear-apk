package mobile

import (
	"bytes"
	"log"
	"sync"
)

// Logger receives server log lines. Implemented on the Android side; gomobile
// turns this into a Java interface.
type Logger interface {
	Log(line string) // one line, no trailing newline
}

const ringCapacity = 2000

// logBridge captures everything upstream writes through the stdlib default
// logger. Every server package uses plain log.Printf (there is no logger
// injection point), so a single log.SetOutput captures all of it.
//
// Upstream logging is extremely chatty — internal/service/octo.go:188-192 dumps
// every request header of every non-asset request — so lines are kept in a ring
// buffer and the Android callback is expected to batch before crossing IPC.
type logBridge struct {
	mu     sync.Mutex
	buf    bytes.Buffer
	ring   []string
	next   int
	filled bool
	sink   Logger
}

var bridge = &logBridge{ring: make([]string, ringCapacity)}

func init() {
	// Drop the stdlib date/time prefix: Android already timestamps logcat, and
	// the UI renders its own.
	log.SetFlags(0)
	log.SetOutput(bridge)
}

// Write implements io.Writer. log.Printf may hand us several lines at once, and
// callers can also write partial lines, so we buffer until a newline.
func (b *logBridge) Write(p []byte) (int, error) {
	b.mu.Lock()
	b.buf.Write(p)
	var lines []string
	for {
		line, err := b.buf.ReadString('\n')
		if err != nil {
			// No newline yet — put the partial line back and wait for more.
			b.buf.Reset()
			b.buf.WriteString(line)
			break
		}
		line = line[:len(line)-1]
		b.store(line)
		lines = append(lines, line)
	}
	sink := b.sink
	b.mu.Unlock()

	// Call out with the lock released: the Android implementation crosses a JNI
	// boundary and must never be able to deadlock the server.
	if sink != nil {
		for _, l := range lines {
			sink.Log(l)
		}
	}
	return len(p), nil
}

// store appends to the ring buffer. Caller holds the lock.
func (b *logBridge) store(line string) {
	b.ring[b.next] = line
	b.next = (b.next + 1) % ringCapacity
	if b.next == 0 {
		b.filled = true
	}
}

func (b *logBridge) setSink(l Logger) {
	b.mu.Lock()
	b.sink = l
	b.mu.Unlock()
}

// snapshot returns buffered lines oldest-first.
func (b *logBridge) snapshot() []string {
	b.mu.Lock()
	defer b.mu.Unlock()
	if !b.filled {
		out := make([]string, b.next)
		copy(out, b.ring[:b.next])
		return out
	}
	out := make([]string, 0, ringCapacity)
	out = append(out, b.ring[b.next:]...)
	out = append(out, b.ring[:b.next]...)
	return out
}
