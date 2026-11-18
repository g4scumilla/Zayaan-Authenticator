const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_uid_123";
const BOB_UID = "bob_uid_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validVaultPayload(uid, overrides = {}) {
  const now = new Date(Date.now() - 2000);
  return {
    userId: uid,
    encryptedPayload: "dGVzdC1lbmNyeXB0ZWQtdmF1bHQtcGF5bG9hZC1jaXBoZXJ0ZXh0",
    vaultIv: "MTIzNDU2Nzg5MDEyMzQ1Ng==",
    wrappedVek: "d3JhcHBlZC12ZWstY2lwaGVydGV4dC13aXRoLWF1dGgtdGFnLXRlc3Q=",
    wrapIv: "MTIzNDU2Nzg5MDEyMzQ1Ng==",
    kdfAlgorithm: "PBKDF2WithHmacSHA256",
    kdfIterations: 600000,
    kdfSalt: "cmFuZG9tLTE2LWJ5dGUtc2FsdA==",
    encryptionVersion: 1,
    schemaVersion: 1,
    backupVersion: 1,
    lastDeviceId: "device-abc-123",
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or write any vault", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("vault").doc("main").get()
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("vault").get()
  );
  await assertFails(
    unauthDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("vault")
      .doc("main")
      .set(validVaultPayload(ALICE_UID))
  );
});

test("Anonymous auth user is rejected", async () => {
  const anonDb = testEnv
    .authenticatedContext("anon_1", {
      firebase: { sign_in_provider: "anonymous" },
    })
    .firestore();
  await assertFails(
    anonDb
      .collection("users")
      .doc("anon_1")
      .collection("vault")
      .doc("main")
      .set(validVaultPayload("anon_1"))
  );
});

test("Owner can create, read, and update vault with strictly increasing backupVersion", async () => {
  const aliceDb = testEnv
    .authenticatedContext(ALICE_UID, {
      firebase: { sign_in_provider: "google.com" },
    })
    .firestore();
  const docRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("vault")
    .doc("main");

  await assertSucceeds(docRef.set(validVaultPayload(ALICE_UID)));
  await assertSucceeds(docRef.get());
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("vault").get()
  );

  // Sync update increments backupVersion
  await assertSucceeds(
    docRef.update({
      encryptedPayload: "bmV3LWVuY3J5cHRlZC1wYXlsb2FkLXYy",
      vaultIv: "bmV3LXJhbmRvbS1pdi12Mg==",
      backupVersion: 2,
      lastDeviceId: "device-xyz-789",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Downgrade or stale backupVersion is rejected
  await assertFails(
    docRef.update({
      encryptedPayload: "c3RhbGUtcGF5bG9hZA==",
      vaultIv: "c3RhbGUtaXYtMTIzNDU2",
      backupVersion: 2,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Password rotation update with new wrappedVek & kdfSalt succeeds
  await assertSucceeds(
    docRef.update({
      encryptedPayload: "cm90YXRlZC1wYXlsb2FkLXYz",
      vaultIv: "cm90YXRlZC1pdi12Mw==",
      wrappedVek: "bmV3LXdyYXBwZWQtdmVrLWNpcGhlcnRleHQtYnl0ZXM=",
      wrapIv: "bmV3LXdyYXAtaXYtMTIzNDU2",
      kdfSalt: "bmV3LXNhbHQtMTYtYnl0ZXM=",
      kdfIterations: 650000,
      backupVersion: 3,
      updatedAt: new Date(Date.now() - 200),
    })
  );
});

test("Cross-user access is strictly blocked", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx
      .firestore()
      .collection("users")
      .doc(ALICE_UID)
      .collection("vault")
      .doc("main")
      .set(validVaultPayload(ALICE_UID));
  });

  const bobDb = testEnv
    .authenticatedContext(BOB_UID, {
      firebase: { sign_in_provider: "google.com" },
    })
    .firestore();

  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("vault").doc("main").get()
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("vault").get()
  );
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("vault")
      .doc("main")
      .update({
        encryptedPayload: "aGlqYWNrZWQtcGF5bG9hZA==",
        backupVersion: 99,
        updatedAt: new Date(Date.now() - 500),
      })
  );
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("vault")
      .doc("main")
      .delete()
  );
});

test("Rejects weak KDF, encryption downgrade, ghost fields, wrong vault doc id, and future timestamps", async () => {
  const aliceDb = testEnv
    .authenticatedContext(ALICE_UID, {
      firebase: { sign_in_provider: "google.com" },
    })
    .firestore();

  // Weak KDF iterations
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("vault")
      .doc("main")
      .set(validVaultPayload(ALICE_UID, { kdfIterations: 10000 }))
  );

  // Encryption downgrade
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("vault")
      .doc("main")
      .set(validVaultPayload(ALICE_UID, { encryptionVersion: 0 }))
  );

  // Ghost field injection
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("vault")
      .doc("main")
      .set(validVaultPayload(ALICE_UID, { recoveryPassword: "leak" }))
  );

  // Wrong vault doc id
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("vault")
      .doc("secondary")
      .set(validVaultPayload(ALICE_UID))
  );

  // Future timestamp
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("vault")
      .doc("main")
      .set(
        validVaultPayload(ALICE_UID, {
          updatedAt: new Date(Date.now() + 60000),
        })
      )
  );
});
