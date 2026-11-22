/**
 * BobrDobr VPN Mobile Client - Unified C-ABI Engine Header
 * Project: БобрДобр VPN ("Центр гидробиологических инноваций")
 *
 * This header defines the binary interface contract bridging Kotlin/Android
 * VpnTunnelService with native protocol engines:
 *   - Rust Engine (libengine_rust.so): AmneziaWG / WireGuard + Zero-alloc TUN Loop
 *   - Go Engine   (libengine_go.so):   OpenConnect CSTP / DTLS + uTLS Chrome Spoofing
 */

#ifndef BOBRDOBR_ENGINE_API_H
#define BOBRDOBR_ENGINE_API_H

#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Engine Status Codes */
#define STATUS_DISCONNECTED   0
#define STATUS_CONNECTING     1
#define STATUS_CONNECTED      2
#define STATUS_DISCONNECTING  3
#define STATUS_RECONNECTING   4
#define STATUS_ERROR          5

/* Engine Result Error Codes */
#define ENGINE_SUCCESS              0
#define ENGINE_ERR_GENERIC         -1
#define ENGINE_ERR_INVALID_ARG     -2
#define ENGINE_ERR_PROTECT_FAILED  -3
#define ENGINE_ERR_SOCKET_INIT     -4
#define ENGINE_ERR_HANDSHAKE       -5
#define ENGINE_ERR_INVALID_CONFIG  -6
#define ENGINE_ERR_ALREADY_RUNNING -7
#define ENGINE_ERR_NOT_RUNNING     -8

#pragma pack(push, 8)
/**
 * Real-time tunnel traffic and telemetry counters.
 */
typedef struct {
    uint64_t rx_bytes;
    uint64_t tx_bytes;
    uint64_t rx_packets;
    uint64_t tx_packets;
    uint64_t rx_speed_bps;
    uint64_t tx_speed_bps;
    uint32_t ping_ms;
    uint32_t reserved;
    uint64_t last_handshake_timestamp;
    uint64_t timestamp;
} tunnel_stats_t;
#pragma pack(pop)

/**
 * Socket protection callback function pointer.
 */
typedef bool (*protect_callback_fn)(int socket_fd);

/**
 * Status callback function pointer.
 */
typedef void (*status_callback_fn)(int status_code, const char* message);

/**
 * Real-time stats callback function pointer.
 */
typedef void (*stats_callback_fn)(uint64_t rx_bytes, uint64_t tx_bytes);

/**
 * Dynamic TUN configuration callback function pointer.
 */
typedef int (*tun_config_callback_fn)(const char* tun_config_json);

#ifndef CGO_ENGINE_INTERNAL
/**
 * Starts the VPN tunnel using the provided TUN file descriptor and JSON configuration.
 */
int start_tunnel(int tun_fd, const char* config_json, protect_callback_fn cb, status_callback_fn scb);

/**
 * Starts the VPN tunnel with dynamic 2-phase TUN configuration support (v2).
 */
int start_tunnel_v2(int tun_fd, const char* config_json, protect_callback_fn cb, status_callback_fn scb, tun_config_callback_fn tcb);

/**
 * Gracefully shuts down the VPN tunnel.
 */
int stop_tunnel(void);

/**
 * Seamlessly updates the physical underlying network without recreating the TUN descriptor.
 */
int update_network(int64_t net_id);

/**
 * Retrieves the current real-time metrics and traffic telemetry from the active engine.
 */
int get_tunnel_stats(tunnel_stats_t* stats);
void set_logging_enabled(bool enabled);
#endif

#ifdef __cplusplus
}
#endif

#endif /* BOBRDOBR_ENGINE_API_H */
