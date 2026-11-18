//! WireGuard and AmneziaWG packet formats, builders, and parsers.

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

/// Identifies packet type taking AmneziaWG H1..H4 custom headers into account.
pub fn parse_packet_type(header: u32, h1: u32, h2: u32, h3: u32, h4: u32) -> PacketType {
    if header == h1 {
        PacketType::Initiation
    } else if header == h2 {
        PacketType::Response
    } else if header == h3 {
        PacketType::CookieReply
    } else if header == h4 {
        PacketType::TransportData
    } else {
        PacketType::Unknown(header)
    }
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
    pub fn serialize(&self, h1: u32, s1_padding: usize) -> Vec<u8> {
        let total_size = INITIATION_SIZE + s1_padding;
        let mut out = vec![0u8; total_size];

        out[0..4].copy_from_slice(&h1.to_le_bytes());
        out[4..8].copy_from_slice(&self.sender_index.to_le_bytes());
        out[8..40].copy_from_slice(&self.unencrypted_ephemeral);
        out[40..88].copy_from_slice(&self.encrypted_static);
        out[88..116].copy_from_slice(&self.encrypted_timestamp);
        out[116..132].copy_from_slice(&self.mac1);
        out[132..148].copy_from_slice(&self.mac2);

        if s1_padding > 0 {
            rand::thread_rng().fill_bytes(&mut out[148..]);
        }

        out
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
    pub fn parse(buf: &[u8], h2: u32) -> Result<Self, String> {
        if buf.len() < RESPONSE_SIZE {
            return Err(format!("Response packet too short: {} < {}", buf.len(), RESPONSE_SIZE));
        }

        let type_tag = u32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]);
        if type_tag != h2 {
            return Err(format!("Unexpected response header type: {}, expected {}", type_tag, h2));
        }

        let sender_index = u32::from_le_bytes([buf[4], buf[5], buf[6], buf[7]]);
        let receiver_index = u32::from_le_bytes([buf[8], buf[9], buf[10], buf[11]]);

        let mut unencrypted_ephemeral = [0u8; 32];
        unencrypted_ephemeral.copy_from_slice(&buf[12..44]);

        let mut encrypted_empty = [0u8; 16];
        encrypted_empty.copy_from_slice(&buf[44..60]);

        let mut mac1 = [0u8; 16];
        mac1.copy_from_slice(&buf[60..76]);

        let mut mac2 = [0u8; 16];
        mac2.copy_from_slice(&buf[76..92]);

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
    pub fn parse(buf: &[u8], h4: u32) -> Result<Self, String> {
        if buf.len() < TRANSPORT_HEADER_SIZE {
            return Err(format!("Transport packet too short: {} < {}", buf.len(), TRANSPORT_HEADER_SIZE));
        }

        let type_tag = u32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]);
        if type_tag != h4 {
            return Err(format!("Unexpected transport header type: {}, expected {}", type_tag, h4));
        }

        let receiver_index = u32::from_le_bytes([buf[4], buf[5], buf[6], buf[7]]);
        let counter = u64::from_le_bytes([
            buf[8], buf[9], buf[10], buf[11],
            buf[12], buf[13], buf[14], buf[15],
        ]);

        Ok(Self {
            receiver_index,
            counter,
        })
    }

    pub fn write_header(buf: &mut [u8], h4: u32, receiver_index: u32, counter: u64) {
        buf[0..4].copy_from_slice(&h4.to_le_bytes());
        buf[4..8].copy_from_slice(&receiver_index.to_le_bytes());
        buf[8..16].copy_from_slice(&counter.to_le_bytes());
    }
}
