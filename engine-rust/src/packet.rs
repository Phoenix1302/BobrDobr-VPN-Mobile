//! WireGuard and AmneziaWG packet formats, builders, and parsers.

use crate::config::HeaderRange;
use rand::RngCore;

pub const INITIATION_SIZE: usize = 148;
pub const RESPONSE_SIZE: usize = 92;
pub const COOKIE_REPLY_SIZE: usize = 64;
pub const TRANSPORT_HEADER_SIZE: usize = 16;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PacketType {
    Initiation,
    Response,
    CookieReply,
    TransportData,
    Unknown(u32),
}

/// Identifies packet type taking AmneziaWG H1..H4 custom headers and S1..S4 prepended padding into account.
pub fn parse_packet_type(
    buf: &[u8],
    h1: &HeaderRange,
    h2: &HeaderRange,
    h3: &HeaderRange,
    h4: &HeaderRange,
    s1: usize,
    s2: usize,
    s3: usize,
    s4: usize,
) -> PacketType {
    // 1. Handshake Response: exact length is s2 + RESPONSE_SIZE (92)
    if buf.len() == s2 + RESPONSE_SIZE {
        let tag = u32::from_le_bytes([buf[s2], buf[s2 + 1], buf[s2 + 2], buf[s2 + 3]]);
        if h2.matches(tag) {
            return PacketType::Response;
        }
    }

    // 2. Transport Data: length is at least s4 + TRANSPORT_HEADER_SIZE (16) + 16 (Poly1305 MAC)
    if buf.len() >= s4 + TRANSPORT_HEADER_SIZE + 16 {
        let tag = u32::from_le_bytes([buf[s4], buf[s4 + 1], buf[s4 + 2], buf[s4 + 3]]);
        if h4.matches(tag) {
            return PacketType::TransportData;
        }
    }

    // 3. Cookie Reply: exact length is s3 + COOKIE_REPLY_SIZE (64)
    if buf.len() == s3 + COOKIE_REPLY_SIZE {
        let tag = u32::from_le_bytes([buf[s3], buf[s3 + 1], buf[s3 + 2], buf[s3 + 3]]);
        if h3.matches(tag) {
            return PacketType::CookieReply;
        }
    }

    // 4. Handshake Initiation: exact length is s1 + INITIATION_SIZE (148)
    if buf.len() == s1 + INITIATION_SIZE {
        let tag = u32::from_le_bytes([buf[s1], buf[s1 + 1], buf[s1 + 2], buf[s1 + 3]]);
        if h1.matches(tag) {
            return PacketType::Initiation;
        }
    }

    let unknown_tag = if buf.len() >= 4 {
        u32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]])
    } else {
        0
    };
    PacketType::Unknown(unknown_tag)
}

/// Creates random AmneziaWG junk packets (Jc packets with size in [Jmin, Jmax]).
pub fn create_junk_packet(jmin: usize, jmax: usize) -> Vec<u8> {
    use rand::Rng;
    let mut rng = rand::thread_rng();
    let size = if jmin >= jmax {
        jmin
    } else {
        rng.gen_range(jmin..=jmax)
    };
    let mut packet = vec![0u8; size];
    rng.fill_bytes(&mut packet);
    packet
}

/// Handshake Initiation packet representation.
#[derive(Debug, Clone)]
pub struct HandshakeInitiation {
    pub sender_index: u32,
    pub unencrypted_ephemeral: [u8; 32],
    pub encrypted_static: [u8; 48],
    pub encrypted_timestamp: [u8; 28],
    pub mac1: [u8; 16],
    pub mac2: [u8; 16],
}

impl HandshakeInitiation {
    /// Serializes initiation packet with prepended S1 random noise bytes.
    /// Wire format: [0..S1: noise] [S1..S1+4: H1] [S1+4..S1+148: WireGuard Body]
    pub fn serialize(&self, h1: u32, s1_padding: usize) -> Vec<u8> {
        let total_size = INITIATION_SIZE + s1_padding;
        let mut out = vec![0u8; total_size];

        if s1_padding > 0 {
            rand::thread_rng().fill_bytes(&mut out[0..s1_padding]);
        }

        let base = s1_padding;
        out[base..base + 4].copy_from_slice(&h1.to_le_bytes());
        out[base + 4..base + 8].copy_from_slice(&self.sender_index.to_le_bytes());
        out[base + 8..base + 40].copy_from_slice(&self.unencrypted_ephemeral);
        out[base + 40..base + 88].copy_from_slice(&self.encrypted_static);
        out[base + 88..base + 116].copy_from_slice(&self.encrypted_timestamp);
        out[base + 116..base + 132].copy_from_slice(&self.mac1);
        out[base + 132..base + 148].copy_from_slice(&self.mac2);

        out
    }

    pub fn parse(buf: &[u8], h1: &HeaderRange, s1_padding: usize) -> Result<Self, String> {
        let expected_len = s1_padding + INITIATION_SIZE;
        if buf.len() < expected_len {
            return Err(format!("Initiation packet too short: {} < {}", buf.len(), expected_len));
        }

        let base = s1_padding;
        let type_tag = u32::from_le_bytes([buf[base], buf[base + 1], buf[base + 2], buf[base + 3]]);
        if !h1.matches(type_tag) {
            return Err(format!("Unexpected initiation header type: {}, expected in range {:?}", type_tag, h1));
        }

        let sender_index = u32::from_le_bytes([buf[base + 4], buf[base + 5], buf[base + 6], buf[base + 7]]);

        let mut unencrypted_ephemeral = [0u8; 32];
        unencrypted_ephemeral.copy_from_slice(&buf[base + 8..base + 40]);

        let mut encrypted_static = [0u8; 48];
        encrypted_static.copy_from_slice(&buf[base + 40..base + 88]);

        let mut encrypted_timestamp = [0u8; 28];
        encrypted_timestamp.copy_from_slice(&buf[base + 88..base + 116]);

        let mut mac1 = [0u8; 16];
        mac1.copy_from_slice(&buf[base + 116..base + 132]);

        let mut mac2 = [0u8; 16];
        mac2.copy_from_slice(&buf[base + 132..base + 148]);

        Ok(Self {
            sender_index,
            unencrypted_ephemeral,
            encrypted_static,
            encrypted_timestamp,
            mac1,
            mac2,
        })
    }
}

/// Handshake Response packet representation.
#[derive(Debug, Clone)]
pub struct HandshakeResponse {
    pub sender_index: u32,
    pub receiver_index: u32,
    pub unencrypted_ephemeral: [u8; 32],
    pub encrypted_empty: [u8; 16],
    pub mac1: [u8; 16],
    pub mac2: [u8; 16],
}

impl HandshakeResponse {
    /// Serializes response packet with prepended S2 random noise bytes.
    pub fn serialize(&self, h2: u32, s2_padding: usize) -> Vec<u8> {
        let total_size = RESPONSE_SIZE + s2_padding;
        let mut out = vec![0u8; total_size];

        if s2_padding > 0 {
            rand::thread_rng().fill_bytes(&mut out[0..s2_padding]);
        }

        let base = s2_padding;
        out[base..base + 4].copy_from_slice(&h2.to_le_bytes());
        out[base + 4..base + 8].copy_from_slice(&self.sender_index.to_le_bytes());
        out[base + 8..base + 12].copy_from_slice(&self.receiver_index.to_le_bytes());
        out[base + 12..base + 44].copy_from_slice(&self.unencrypted_ephemeral);
        out[base + 44..base + 60].copy_from_slice(&self.encrypted_empty);
        out[base + 60..base + 76].copy_from_slice(&self.mac1);
        out[base + 76..base + 92].copy_from_slice(&self.mac2);

        out
    }

    /// Parses incoming response with prepended S2 random noise bytes.
    pub fn parse(buf: &[u8], h2: &HeaderRange, s2_padding: usize) -> Result<Self, String> {
        let expected_len = s2_padding + RESPONSE_SIZE;
        if buf.len() < expected_len {
            return Err(format!("Response packet too short: {} < {}", buf.len(), expected_len));
        }

        let base = s2_padding;
        let type_tag = u32::from_le_bytes([buf[base], buf[base + 1], buf[base + 2], buf[base + 3]]);
        if !h2.matches(type_tag) {
            return Err(format!("Unexpected response header type: {}, expected in range {:?}", type_tag, h2));
        }

        let sender_index = u32::from_le_bytes([buf[base + 4], buf[base + 5], buf[base + 6], buf[base + 7]]);
        let receiver_index = u32::from_le_bytes([buf[base + 8], buf[base + 9], buf[base + 10], buf[base + 11]]);

        let mut unencrypted_ephemeral = [0u8; 32];
        unencrypted_ephemeral.copy_from_slice(&buf[base + 12..base + 44]);

        let mut encrypted_empty = [0u8; 16];
        encrypted_empty.copy_from_slice(&buf[base + 44..base + 60]);

        let mut mac1 = [0u8; 16];
        mac1.copy_from_slice(&buf[base + 60..base + 76]);

        let mut mac2 = [0u8; 16];
        mac2.copy_from_slice(&buf[base + 76..base + 92]);

        Ok(Self {
            sender_index,
            receiver_index,
            unencrypted_ephemeral,
            encrypted_empty,
            mac1,
            mac2,
        })
    }
}

/// Transport Data packet header parser.
pub struct TransportHeader {
    pub receiver_index: u32,
    pub counter: u64,
}

impl TransportHeader {
    pub fn parse(buf: &[u8], h4: &HeaderRange, s4_padding: usize) -> Result<Self, String> {
        let expected_min = s4_padding + TRANSPORT_HEADER_SIZE;
        if buf.len() < expected_min {
            return Err(format!("Transport packet too short: {} < {}", buf.len(), expected_min));
        }

        let base = s4_padding;
        let type_tag = u32::from_le_bytes([buf[base], buf[base + 1], buf[base + 2], buf[base + 3]]);
        if !h4.matches(type_tag) {
            return Err(format!("Unexpected transport header type: {}, expected in range {:?}", type_tag, h4));
        }

        let receiver_index = u32::from_le_bytes([buf[base + 4], buf[base + 5], buf[base + 6], buf[base + 7]]);
        let counter = u64::from_le_bytes([
            buf[base + 8], buf[base + 9], buf[base + 10], buf[base + 11],
            buf[base + 12], buf[base + 13], buf[base + 14], buf[base + 15],
        ]);

        Ok(Self {
            receiver_index,
            counter,
        })
    }

    pub fn write_header(buf: &mut [u8], h4: u32, receiver_index: u32, counter: u64, s4_padding: usize) {
        if s4_padding > 0 {
            rand::thread_rng().fill_bytes(&mut buf[0..s4_padding]);
        }
        let base = s4_padding;
        buf[base..base + 4].copy_from_slice(&h4.to_le_bytes());
        buf[base + 4..base + 8].copy_from_slice(&receiver_index.to_le_bytes());
        buf[base + 8..base + 16].copy_from_slice(&counter.to_le_bytes());
    }
}
