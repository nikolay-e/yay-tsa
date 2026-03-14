-- V11: Security hardening
-- Revoke all existing plaintext tokens; new tokens will be stored as SHA-256 hashes.
UPDATE api_tokens SET revoked = true WHERE revoked = false;
