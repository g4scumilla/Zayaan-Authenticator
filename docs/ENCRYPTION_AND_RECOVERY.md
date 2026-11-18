# Zayan Authenticator — Zero-Knowledge Encryption & Recovery Architecture

## 1. Threat Model & Core Guarantees

Zayan Authenticator implements **genuine client-side zero-knowledge encryption**:

- **Firebase Auth (Google Sign-In)** is used strictly for user identity and authenticating Firestore access to `users/{uid}/vault/main`.
- **Google Sign-In alone can NEVER decrypt a user's authenticator vault.**
- **Cloud Firestore (Spark free tier)** stores only authenticated ciphertext (`AES-256-GCM`), wrapped key material, and non-secret KDF parameters.
- Plaintext TOTP secrets, recovery passwords, KEKs, and unwrapped VEKs are **never** uploaded to Firebase or written to application logs.

## 2. Key Hierarchy

1. **Recovery Password** (`>= 12` characters; passphrase recommended):
   - Entered by the user during vault initialization or device recovery.
   - Never stored on disk or in Firestore.
2. **Key Encryption Key (KEK — 256-bit AES)**:
   - Derived on-device via `PBKDF2WithHmacSHA256` using `600,000` iterations and a 128-bit (`16`-byte) cryptographically random salt (`SecureRandom`).
3. **Vault Encryption Key (VEK — 256-bit AES)**:
   - Generated randomly on-device via `SecureRandom`.
   - Wrapped with `KEK` using `AES-256-GCM` with a fresh 96-bit (`12`-byte) random IV and AAD `"zayan-vek-v1:{uid}"`.
4. **Vault Payload Encryption**:
   - Canonical JSON of `TotpAccount` records and `DeletedAccountRecord` tombstones is encrypted with `VEK` using `AES-256-GCM`, a fresh 96-bit random IV on every write, and AAD `"zayan-vault-v1:{uid}:v{backupVersion}"`.

## 3. Local At-Rest Storage

- On-device persistence (`LocalVaultStorage`) seals the local `LocalVaultEnvelope` using a hardware-backed **Android Keystore** `AES-256-GCM` master key (`zayan_local_master_key_v1`) with randomized encryption required.
- `android:allowBackup="false"` is configured in `AndroidManifest.xml` to prevent OS-level unencrypted cloud backups of local key material.

## 4. Multi-Device Synchronization & Conflict Safety

- Cloud vault updates use Firestore transactions verifying `remote.backupVersion == expectedRemoteVersion`.
- Security rules enforce `incoming().backupVersion > existing().backupVersion`, preventing stale devices from overwriting newer backups.
- Concurrent changes across devices trigger a deterministic merge (`VaultMergeEngine`) preserving additions, latest edits (`updatedAtMs`), and cross-device deletions (`DeletedAccountRecord` tombstones).

## 5. Recovery & No-Backdoor Policy

- On a new device or after reinstallation, signing in with Google detects `users/{uid}/vault/main`, prompts for the recovery password, derives the KEK locally, unwraps the VEK, and decrypts the vault.
- Incorrect passwords fail `AES-GCM` authentication (`AEADBadTagException`) without modifying or corrupting the cloud backup.
- No password reset mechanism can bypass the recovery password.
