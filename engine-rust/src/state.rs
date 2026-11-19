//! WireGuard protocol state machine, Noise IKpsk2 handshakes, and transport sessions.

use crate::crypto::*;
use crate::packet::*;
use rand::Rng;

pub const REKEY_AFTER_MESSAGES: u64 = 1 << 60;
pub const REJECT_AFTER_MESSAGES: u64 = u64::MAX - (1 << 13);
pub const REKEY_AFTER_TIME_SECS: u64 = 120;
pub const HANDSHAKE_TIMEOUT_SECS: u64 = 5;

#[derive(Debug, Default, Clone)]
pub struct ReplayWindow {
    max_counter: u64,
    bitmap: u128,
}

impl ReplayWindow {
    pub fn new() -> Self {
        Self {
            max_counter: 0,
            bitmap: 0,
        }
    }

    /// Checks if counter was seen. If not seen, records it and returns true.
    pub fn check_and_update(&mut self, counter: u64) -> bool {
        if counter > self.max_counter {
            let shift = counter - self.max_counter;
            if shift < 128 {
                self.bitmap = (self.bitmap << shift) | 1;
            } else {
                self.bitmap = 1;
            }
            self.max_counter = counter;
            true
        } else {
            let diff = self.max_counter - counter;
            if diff >= 128 {
                return false; // Packet too old
            }
            let bit = 1u128 << diff;
            if self.bitmap & bit != 0 {
                return false; // Replayed packet!
            }
            self.bitmap |= bit;
            true
        }
    }
}

pub struct TransportKeypair {
    pub send_key: [u8; 32],
    pub recv_key: [u8; 32],
    pub send_counter: u64,
    pub local_index: u32,
    pub remote_index: u32,
    pub replay_window: ReplayWindow,
    pub birth_timestamp: u64,
}

impl TransportKeypair {
    pub fn new(
        send_key: [u8; 32],
        recv_key: [u8; 32],
        local_index: u32,
        remote_index: u32,
    ) -> Self {
        Self {
            send_key,
            recv_key,
            send_counter: 0,
            local_index,
            remote_index,
            replay_window: ReplayWindow::new(),
            birth_timestamp: current_timestamp_ms(),
        }
    }

    pub fn next_send_counter(&mut self) -> Result<u64, String> {
        let counter = self.send_counter;
        if counter >= REJECT_AFTER_MESSAGES {
            return Err("Counter overflow, session expired".to_string());
        }
        self.send_counter += 1;
        Ok(counter)
    }
}

pub struct SessionState {
    pub local_priv: [u8; 32],
    pub local_pub: [u8; 32],
    pub remote_pub: [u8; 32],
    pub preshared_key: Option<[u8; 32]>,

    // Ephemeral handshake state
    pub ephemeral_priv: Option<[u8; 32]>,
    pub ephemeral_pub: Option<[u8; 32]>,
    pub local_index: u32,
    pub chaining_key: [u8; 32],
    pub hash: [u8; 32],

    // Active transport session
    pub current_keypair: Option<TransportKeypair>,
    pub previous_keypair: Option<TransportKeypair>,

    // Timestamps
    pub last_handshake_sent: u64,
    pub last_handshake_completed: u64,
    pub last_tx_time: u64,
    pub last_rx_time: u64,
}

pub fn current_timestamp_ms() -> u64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

impl SessionState {
    pub fn new(
        local_priv: [u8; 32],
        remote_pub: [u8; 32],
        preshared_key: Option<[u8; 32]>,
    ) -> Self {
        let local_pub = public_from_private(&local_priv);
        Self {
            local_priv,
            local_pub,
            remote_pub,
            preshared_key,
            ephemeral_priv: None,
            ephemeral_pub: None,
            local_index: rand::thread_rng().gen_range(1..=u32::MAX - 1),
            chaining_key: [0u8; 32],
            hash: [0u8; 32],
            current_keypair: None,
            previous_keypair: None,
            last_handshake_sent: 0,
            last_handshake_completed: 0,
            last_tx_time: 0,
            last_rx_time: 0,
        }
    }

    /// Initializes and builds a Noise_IKpsk2 Handshake Initiation packet.
    /// In AmneziaWG, MAC1 is computed over msg[0..116] starting with custom header H1.
    pub fn create_initiation(&mut self, h1: u32) -> Result<HandshakeInitiation, String> {
        let (e_priv, e_pub) = generate_ephemeral_keypair();
        self.ephemeral_priv = Some(e_priv);
        self.ephemeral_pub = Some(e_pub);
        self.local_index = rand::thread_rng().gen_range(1..=u32::MAX - 1);

        // 1. C_0 = Hash(CONSTRUCTION)
        let c_0 = blake2s_256(CONSTRUCTION);

        // 2. H_0 = Hash(C_0 || IDENTIFIER)
        let mut h_0_input = Vec::with_capacity(32 + IDENTIFIER.len());
        h_0_input.extend_from_slice(&c_0);
        h_0_input.extend_from_slice(IDENTIFIER);
        let h_0 = blake2s_256(&h_0_input);

        // 3. H_1 = Hash(H_0 || Spk)
        let mut h_1_input = Vec::with_capacity(64);
        h_1_input.extend_from_slice(&h_0);
        h_1_input.extend_from_slice(&self.remote_pub);
        let h_1 = blake2s_256(&h_1_input);

        // 4. H_2 = Hash(H_1 || e_pub)
        let mut h_2_input = Vec::with_capacity(64);
        h_2_input.extend_from_slice(&h_1);
        h_2_input.extend_from_slice(&e_pub);
        let h_2 = blake2s_256(&h_2_input);

        // 5. ss1 = X25519(e_priv, Spk)
        let ss1 = x25519(&e_priv, &self.remote_pub);

        // 6. (C_1, k_1) = KDF2(C_0, ss1)
        let (c_1, k_1) = kdf2(&c_0, &ss1);

        // 7. encrypted_static = AEAD(k_1, 0, Cpk, H_2)
        let encrypted_static_vec = aead_encrypt(&k_1, 0, &self.local_pub, &h_2)?;
        if encrypted_static_vec.len() != 48 {
            return Err("Invalid encrypted static length".to_string());
        }
        let mut encrypted_static = [0u8; 48];
        encrypted_static.copy_from_slice(&encrypted_static_vec);

        // 8. H_3 = Hash(H_2 || encrypted_static)
        let mut h_3_input = Vec::with_capacity(32 + 48);
        h_3_input.extend_from_slice(&h_2);
        h_3_input.extend_from_slice(&encrypted_static);
        let h_3 = blake2s_256(&h_3_input);

        // 9. ss2 = X25519(C_priv, Spk)
        let ss2 = x25519(&self.local_priv, &self.remote_pub);

        // 10. (C_2, k_2) = KDF2(C_1, ss2)
        let (c_2, k_2) = kdf2(&c_1, &ss2);

        // 11. encrypted_timestamp = AEAD(k_2, 0, Tai64n, H_3)
        let tai = current_tai64n();
        let encrypted_timestamp_vec = aead_encrypt(&k_2, 0, &tai, &h_3)?;
        if encrypted_timestamp_vec.len() != 28 {
            return Err("Invalid encrypted timestamp length".to_string());
        }
        let mut encrypted_timestamp = [0u8; 28];
        encrypted_timestamp.copy_from_slice(&encrypted_timestamp_vec);

        // 12. H_4 = Hash(H_3 || encrypted_timestamp)
        let mut h_4_input = Vec::with_capacity(32 + 28);
        h_4_input.extend_from_slice(&h_3);
        h_4_input.extend_from_slice(&encrypted_timestamp);
        let h_4 = blake2s_256(&h_4_input);

        // Save state for response processing
        self.chaining_key = c_2;
        self.hash = h_4;
        self.last_handshake_sent = current_timestamp_ms();

        // 13. MAC1 = MAC(Hash(LABEL_MAC1 || Spk), msg[0..116])
        // CRITICAL FOR AMNEZIAWG: The packet begins with H1 (little-endian u32), NOT standard WireGuard type 1!
        let mut mac1_key_input = Vec::with_capacity(8 + 32);
        mac1_key_input.extend_from_slice(LABEL_MAC1);
        mac1_key_input.extend_from_slice(&self.remote_pub);
        let mac1_key = blake2s_256(&mac1_key_input);

        let mut pre_mac_data = Vec::with_capacity(116);
        pre_mac_data.extend_from_slice(&h1.to_le_bytes()); // AmneziaWG packet header H1
        pre_mac_data.extend_from_slice(&self.local_index.to_le_bytes());
        pre_mac_data.extend_from_slice(&e_pub);
        pre_mac_data.extend_from_slice(&encrypted_static);
        pre_mac_data.extend_from_slice(&encrypted_timestamp);

        let mac1 = blake2s_mac128(&mac1_key, &pre_mac_data)?;
        let mac2 = [0u8; 16]; // No active cookie challenge

        Ok(HandshakeInitiation {
            sender_index: self.local_index,
            unencrypted_ephemeral: e_pub,
            encrypted_static,
            encrypted_timestamp,
            mac1,
            mac2,
        })
    }

    /// Processes a Handshake Response from the peer and derives transport keys.
    pub fn consume_response(&mut self, resp: &HandshakeResponse) -> Result<u32, String> {
        let e_priv = self.ephemeral_priv
            .ok_or_else(|| "No active handshake initiation found".to_string())?;

        if resp.receiver_index != self.local_index {
            return Err(format!(
                "Response receiver_index mismatch: {} != local {}",
                resp.receiver_index, self.local_index
            ));
        }

        // 1. H_5 = Hash(H_4 || e_peer_pub)
        let mut h_5_input = Vec::with_capacity(64);
        h_5_input.extend_from_slice(&self.hash);
        h_5_input.extend_from_slice(&resp.unencrypted_ephemeral);
        let h_5 = blake2s_256(&h_5_input);

        // 2. ss3 = X25519(e_priv, e_peer_pub)
        let ss3 = x25519(&e_priv, &resp.unencrypted_ephemeral);

        // 3. ss4 = X25519(C_priv, e_peer_pub)
        let ss4 = x25519(&self.local_priv, &resp.unencrypted_ephemeral);

        // 4. (C_3, _) = KDF1(C_2, ss3)
        let c_3 = kdf1(&self.chaining_key, &ss3);

        // 5. (C_4, _) = KDF1(C_3, ss4)
        let c_4 = kdf1(&c_3, &ss4);

        // 6. (C_5, tau, k_3) = KDF3(C_4, psk)
        let psk = self.preshared_key.unwrap_or([0u8; 32]);
        let (c_5, tau, k_3) = kdf3(&c_4, &psk);

        // 7. H_6 = Hash(H_5 || tau)
        let mut h_6_input = Vec::with_capacity(64);
        h_6_input.extend_from_slice(&h_5);
        h_6_input.extend_from_slice(&tau);
        let h_6 = blake2s_256(&h_6_input);

        // 8. Decrypt empty payload
        let decrypted_empty = aead_decrypt(&k_3, 0, &resp.encrypted_empty, &h_6)?;
        if !decrypted_empty.is_empty() {
            return Err("Handshake response decrypted payload not empty".to_string());
        }

        // 9. Derive Transport Keys: (T_send, T_recv) = KDF2(C_5, [])
        let (t_send, t_recv) = kdf2(&c_5, &[]);

        // Rotate keypairs
        let new_keypair = TransportKeypair::new(
            t_send,
            t_recv,
            self.local_index,
            resp.sender_index,
        );

        self.previous_keypair = self.current_keypair.take();
        self.current_keypair = Some(new_keypair);

        self.last_handshake_completed = current_timestamp_ms();
        self.ephemeral_priv = None;
        self.ephemeral_pub = None;

        let rtt_ms = self.last_handshake_completed.saturating_sub(self.last_handshake_sent);
        Ok(rtt_ms as u32)
    }

    /// Check if rekeying is needed
    pub fn should_rekey(&self) -> bool {
        if let Some(ref kp) = self.current_keypair {
            if kp.send_counter >= REKEY_AFTER_MESSAGES {
                return true;
            }
            let now = current_timestamp_ms();
            let elapsed_secs = (now.saturating_sub(self.last_handshake_completed)) / 1000;
            if elapsed_secs >= REKEY_AFTER_TIME_SECS {
                return true;
            }
        } else {
            // No active keypair yet
            let now = current_timestamp_ms();
            let elapsed_secs = (now.saturating_sub(self.last_handshake_sent)) / 1000;
            if elapsed_secs >= HANDSHAKE_TIMEOUT_SECS {
                return true;
            }
        }
        false
    }
}
