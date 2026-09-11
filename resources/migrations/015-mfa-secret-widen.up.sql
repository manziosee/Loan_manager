-- users.mfa_secret was VARCHAR(100), sized for a raw base32 TOTP secret
-- (~32 chars). Now stores AES-GCM ciphertext (IV + tag + base64 overhead),
-- which doesn't reliably fit — widen to TEXT.
ALTER TABLE users ALTER COLUMN mfa_secret TYPE TEXT
