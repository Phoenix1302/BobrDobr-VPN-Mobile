//! WireGuard & AmneziaWG cryptographic primitives and Noise IKpsk2 implementation.

use blake2::digest::typenum::U16;
use blake2::digest::{Digest, Mac};
use blake2::{Blake2s256, Blake2sMac};
use chacha20poly1305::aead::{AeadInPlace, KeyInit};
use chacha20poly1305::{ChaCha20Poly1305, Nonce, Tag};
use x25519_dalek::{PublicKey as DalekPublicKey, StaticSecret};

pub const CONSTRUCTION: &[u8] = b"Noise_IKpsk2_25519_ChaChaPoly_BLAKE2s";
pub const IDENTIFIER: &[u8] = b"WireGuard v1 zx2c4 JustAGuess";
pub const LABEL_MAC1: &[u8] = b"mac1----";
pub const LABEL_COOKIE: &[u8] = b"cookie--";

pub fn blake2s_256(data: &[u8]) -> [u8; 32] {
    let mut h = Blake2s256::new();
    h.update(data);
    let mut out = [0u8; 32];
    out.copy_from_slice(&h.finalize());
    out
}

pub fn blake2s_mac128(key: &[u8], data: &[u8]) -> Result<[u8; 16], String> {
    let mut mac = <Blake2sMac<U16> as Mac>::new_from_slice(key)
        .map_err(|e| format!("Blake2sMac init error: {:?}", e))?;
    mac.update(data);
    let mut out = [0u8; 16];
    out.copy_from_slice(&mac.finalize().into_bytes());
    Ok(out)
}

pub fn hmac_blake2s(key: &[u8], data: &[u8]) -> [u8; 32] {
    let mut k = [0u8; 64];
    if key.len() > 64 {
        let hash = blake2s_256(key);
        k[..32].copy_from_slice(&hash);
    } else {
        k[..key.len()].copy_from_slice(key);
    }

    let mut ipad = [0x36u8; 64];
    let mut opad = [0x5cu8; 64];
    for i in 0..64 {
        ipad[i] ^= k[i];
        opad[i] ^= k[i];
    }

    let mut inner = Blake2s256::new();
    inner.update(&ipad);
    inner.update(data);
    let inner_hash = inner.finalize();

    let mut outer = Blake2s256::new();
    outer.update(&opad);
    outer.update(&inner_hash);

    let mut out = [0u8; 32];
    out.copy_from_slice(&outer.finalize());
    out
}

pub fn kdf1(key: &[u8; 32], input: &[u8]) -> [u8; 32] {
    let t0 = hmac_blake2s(key, input);
    hmac_blake2s(&t0, &[0x01])
}

pub fn kdf2(key: &[u8; 32], input: &[u8]) -> ([u8; 32], [u8; 32]) {
    let t0 = hmac_blake2s(key, input);
    let t1 = hmac_blake2s(&t0, &[0x01]);
    let mut t2_input = Vec::with_capacity(33);
    t2_input.extend_from_slice(&t1);
    t2_input.push(0x02);
    let t2 = hmac_blake2s(&t0, &t2_input);
    (t1, t2)
}

pub fn kdf3(key: &[u8; 32], input: &[u8]) -> ([u8; 32], [u8; 32], [u8; 32]) {
    let t0 = hmac_blake2s(key, input);
    let t1 = hmac_blake2s(&t0, &[0x01]);

    let mut t2_input = Vec::with_capacity(33);
    t2_input.extend_from_slice(&t1);
    t2_input.push(0x02);
    let t2 = hmac_blake2s(&t0, &t2_input);

    let mut t3_input = Vec::with_capacity(33);
    t3_input.extend_from_slice(&t2);
    t3_input.push(0x03);
    let t3 = hmac_blake2s(&t0, &t3_input);

    (t1, t2, t3)
}

pub fn x25519(priv_key: &[u8; 32], pub_key: &[u8; 32]) -> [u8; 32] {
    let secret = StaticSecret::from(*priv_key);
    let public = DalekPublicKey::from(*pub_key);
    let shared = secret.diffie_hellman(&public);
    *shared.as_bytes()
}

pub fn public_from_private(priv_key: &[u8; 32]) -> [u8; 32] {
    let secret = StaticSecret::from(*priv_key);
    let public = DalekPublicKey::from(&secret);
    *public.as_bytes()
}

pub fn generate_ephemeral_keypair() -> ([u8; 32], [u8; 32]) {
    use rand::RngCore;
    let mut rng = rand::rngs::OsRng;
    let mut priv_key = [0u8; 32];
    rng.fill_bytes(&mut priv_key);

    // Clamp private key for curve25519
    priv_key[0] &= 248;
    priv_key[31] &= 127;
    priv_key[31] |= 64;

    let pub_key = public_from_private(&priv_key);
    (priv_key, pub_key)
}

pub fn make_nonce(counter: u64) -> [u8; 12] {
    let mut nonce = [0u8; 12];
    nonce[4..12].copy_from_slice(&counter.to_le_bytes());
    nonce
}

pub fn aead_encrypt(key: &[u8; 32], counter: u64, plaintext: &[u8], ad: &[u8]) -> Result<Vec<u8>, String> {
    let cipher = ChaCha20Poly1305::new_from_slice(key)
        .map_err(|e| format!("ChaCha20Poly1305 new error: {:?}", e))?;
    let nonce_bytes = make_nonce(counter);
    let nonce = Nonce::from_slice(&nonce_bytes);

    let mut buffer = plaintext.to_vec();
    let tag = cipher.encrypt_in_place_detached(nonce, ad, &mut buffer)
        .map_err(|e| format!("Encryption error: {:?}", e))?;
    buffer.extend_from_slice(tag.as_slice());
    Ok(buffer)
}

pub fn aead_encrypt_in_place(
    key: &[u8; 32],
    counter: u64,
    buffer: &mut [u8],
    plaintext_len: usize,
    ad: &[u8],
) -> Result<usize, String> {
    if buffer.len() < plaintext_len + 16 {
        return Err("Buffer too small for ciphertext + tag".to_string());
    }

    let cipher = ChaCha20Poly1305::new_from_slice(key)
        .map_err(|e| format!("ChaCha20Poly1305 new error: {:?}", e))?;
    let nonce_bytes = make_nonce(counter);
    let nonce = Nonce::from_slice(&nonce_bytes);

    let tag = cipher.encrypt_in_place_detached(nonce, ad, &mut buffer[..plaintext_len])
        .map_err(|e| format!("Encryption error: {:?}", e))?;

    buffer[plaintext_len..plaintext_len + 16].copy_from_slice(tag.as_slice());
    Ok(plaintext_len + 16)
}

pub fn aead_decrypt(key: &[u8; 32], counter: u64, ciphertext: &[u8], ad: &[u8]) -> Result<Vec<u8>, String> {
    if ciphertext.len() < 16 {
        return Err("Ciphertext too short".to_string());
    }
    let tag_offset = ciphertext.len() - 16;
    let mut buffer = ciphertext[..tag_offset].to_vec();
    let tag = Tag::from_slice(&ciphertext[tag_offset..]);

    let cipher = ChaCha20Poly1305::new_from_slice(key)
        .map_err(|e| format!("ChaCha20Poly1305 new error: {:?}", e))?;
    let nonce_bytes = make_nonce(counter);
    let nonce = Nonce::from_slice(&nonce_bytes);

    cipher.decrypt_in_place_detached(nonce, ad, &mut buffer, tag)
        .map_err(|e| format!("Decryption error: {:?}", e))?;

    Ok(buffer)
}

pub fn aead_decrypt_in_place(
    key: &[u8; 32],
    counter: u64,
    buffer: &mut [u8],
    ciphertext_len: usize,
    ad: &[u8],
) -> Result<usize, String> {
    if ciphertext_len < 16 {
        return Err("Ciphertext too short".to_string());
    }
    let plaintext_len = ciphertext_len - 16;
    let tag = Tag::clone_from_slice(&buffer[plaintext_len..ciphertext_len]);

    let cipher = ChaCha20Poly1305::new_from_slice(key)
        .map_err(|e| format!("ChaCha20Poly1305 new error: {:?}", e))?;
    let nonce_bytes = make_nonce(counter);
    let nonce = Nonce::from_slice(&nonce_bytes);

    cipher.decrypt_in_place_detached(nonce, ad, &mut buffer[..plaintext_len], &tag)
        .map_err(|e| format!("Decryption error: {:?}", e))?;

    Ok(plaintext_len)
}

pub fn current_tai64n() -> [u8; 12] {
    tai64::Tai64N::now().to_bytes()
}
