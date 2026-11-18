// Command quicprobe measures how a host is reachable from the machine it runs
// on: TCP 443 with a real TLS handshake, and UDP 443 with a QUIC Version
// Negotiation trigger.
//
// The trigger matters. A plain UDP packet tells you nothing, because a QUIC
// server drops what it cannot parse. Sending a long-header packet that claims a
// version no server implements makes a QUIC endpoint answer with Version
// Negotiation, without any cryptography involved — so "UDP 443 is blocked" can
// be told apart from "this host does not speak QUIC".
//
// The point is the comparison: on a network that resets TLS handshakes by name,
// TCP fails while UDP still answers, which is the precondition for carrying the
// proxy's upstream over QUIC.
//
// Addresses are passed in rather than resolved here: a Go binary on Android has
// no /etc/resolv.conf, so it cannot use the device's resolver. Resolve first
// (ping, or the app) and pass host=ip.
//
//	quicprobe x.com=203.0.113.10 example.com=203.0.113.11
package main

import (
	"crypto/rand"
	"crypto/tls"
	"encoding/binary"
	"fmt"
	"net"
	"os"
	"time"
)

func main() {
	timeout := 5 * time.Second
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: quicprobe host=ip [host=ip...]")
		os.Exit(2)
	}
	fmt.Printf("%-28s %-30s %-30s\n", "host", "tcp+tls", "udp443 (quic)")
	for _, arg := range os.Args[1:] {
		host, ip := splitPair(arg)
		okTCP, dTCP, msgTCP := tcpTLS(host, ip, timeout)
		okUDP, dUDP, msgUDP := udpQUIC(ip, timeout)
		fmt.Printf("%-28s %-30s %-30s\n", host, result(okTCP, dTCP, msgTCP), result(okUDP, dUDP, msgUDP))
	}
}

func splitPair(arg string) (host, ip string) {
	for i := 0; i < len(arg); i++ {
		if arg[i] == '=' {
			return arg[:i], arg[i+1:]
		}
	}
	return arg, arg
}

func result(ok bool, d time.Duration, msg string) string {
	mark := "FAIL"
	if ok {
		mark = "ok"
	}
	return fmt.Sprintf("%s %5dms %s", mark, d.Milliseconds(), msg)
}

// tcpTLS dials ip but verifies the certificate against host, which is what the
// browser would do: a connection that completes but cannot be verified is not a
// usable path.
func tcpTLS(host, ip string, timeout time.Duration) (bool, time.Duration, string) {
	start := time.Now()
	conn, err := net.DialTimeout("tcp", net.JoinHostPort(ip, "443"), timeout)
	if err != nil {
		return false, time.Since(start), "tcp: " + short(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(timeout))
	tc := tls.Client(conn, &tls.Config{ServerName: host, MinVersion: tls.VersionTLS12})
	if err := tc.Handshake(); err != nil {
		return false, time.Since(start), "tls: " + short(err)
	}
	return true, time.Since(start), "ok"
}

// udpQUIC sends a QUIC long-header packet with a version no server implements.
// A QUIC endpoint answers with Version Negotiation; anything else stays silent.
func udpQUIC(ip string, timeout time.Duration) (bool, time.Duration, string) {
	addr, err := net.ResolveUDPAddr("udp", net.JoinHostPort(ip, "443"))
	if err != nil {
		return false, 0, "resolve: " + short(err)
	}
	conn, err := net.DialUDP("udp", nil, addr)
	if err != nil {
		return false, 0, "dial: " + short(err)
	}
	defer conn.Close()

	// Servers ignore datagrams below the minimum QUIC size, so the probe has to
	// look like a real Initial packet before the version check.
	pkt := make([]byte, 1200)
	pkt[0] = 0xC0 // long header form, fixed bit
	binary.BigEndian.PutUint32(pkt[1:5], 0x0a0a0a0a)
	dcid := make([]byte, 8)
	_, _ = rand.Read(dcid)
	pkt[5] = byte(len(dcid))
	copy(pkt[6:], dcid)
	pkt[14] = 0 // no source connection id

	start := time.Now()
	if _, err := conn.Write(pkt); err != nil {
		return false, time.Since(start), "write: " + short(err)
	}
	_ = conn.SetReadDeadline(time.Now().Add(timeout))
	buf := make([]byte, 1500)
	n, err := conn.Read(buf)
	if err != nil {
		return false, time.Since(start), "no reply: " + short(err)
	}
	if n >= 5 && binary.BigEndian.Uint32(buf[1:5]) == 0 {
		return true, time.Since(start), "version negotiation"
	}
	return true, time.Since(start), fmt.Sprintf("%d bytes", n)
}

func short(err error) string {
	s := err.Error()
	if len(s) > 48 {
		return s[:48]
	}
	return s
}
