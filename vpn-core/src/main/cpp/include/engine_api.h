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
    uint64_t last_handshake_timestamp;
    uint64_t timestamp;
} tunnel_stats_t;
#pragma pack(pop)

/**
 * Socket protection callback function pointer.
 * MUST be invoked synchronously on newly created transport sockets (UDP / TCP)
 * BEFORE calling connect() or sending packets, ensuring the socket is bound
 * outside the VPN routing table via Android VpnService.protect(int fd).
 *
 * @param socket_fd Raw OS file descriptor of the transport socket.
 * @return true if protected successfully by Android OS netd, false otherwise.
 */
typedef bool (*protect_callback_fn)(int socket_fd);

/**
 * Status callback function pointer.
 * Invoked by native engines to communicate state transitions and operational errors.
 *
 * @param status_code One of the STATUS_* values.
 * @param message Null-terminated UTF-8 descriptive status or error message.
 */
typedef void (*status_callback_fn)(int status_code, const char* message);

/**
 * Real-time stats callback function pointer.
 *
 * @param rx_bytes Instantaneous total received bytes.
 * @param tx_bytes Instantaneous total transmitted bytes.
 */
typedef void (*stats_callback_fn)(uint64_t rx_bytes, uint64_t tx_bytes);

/**
 * Starts the VPN tunnel using the provided TUN file descriptor and JSON configuration.
 *
 * @param tun_fd File descriptor of the TUN interface detached from Android ParcelFileDescriptor.
 * @param config_json Protocol configuration in JSON format (keys, obfuscation, endpoints).
 * @param cb Synchronous socket protection callback to Android VpnService.
 * @param scb Asynchronous status and error reporting callback to Android Service.
 * @return ENGINE_SUCCESS (0) on success, or a negative ENGINE_ERR_* code.
 */
int start_tunnel(int tun_fd, const char* config_json, protect_callback_fn cb, status_callback_fn scb);

/**
 * Gracefully shuts down the VPN tunnel.
 * Sends disconnect control frames (e.g., CSTP BYE in AnyConnect), terminates I/O loops,
 * and frees all allocated socket and memory resources.
 *
 * @return ENGINE_SUCCESS (0) on success, or a negative ENGINE_ERR_* code.
 */
int stop_tunnel(void);

/**
 * Seamlessly updates the physical underlying network without recreating the TUN descriptor.
 * Enables zero-interruption roaming between Wi-Fi and Cellular (LTE/5G) connections.
 *
 * @param net_id 64-bit Android network handle (Network.getNetworkHandle()) or 0 for default.
 * @return ENGINE_SUCCESS (0) on success, or a negative ENGINE_ERR_* code.
 */
int update_network(int64_t net_id);

/**
 * Retrieves the current real-time metrics and traffic telemetry from the active engine.
 *
 * @param stats Pointer to a tunnel_stats_t struct to populate.
 * @return ENGINE_SUCCESS (0) on success, or a negative ENGINE_ERR_* code.
 */
int get_tunnel_stats(tunnel_stats_t* stats);

#ifdef __cplusplus
}
#endif

#endif /* BOBRDOBR_ENGINE_API_H */
