const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

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

test("Unauthenticated user cannot read or write videos", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("videos").get()
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("videos").doc("vid_1").set({
      userId: ALICE_UID,
      title: "Test",
    })
  );
});

test("Authenticated user can create, read, and update their own video with valid schema", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date();
  const videoRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("videos")
    .doc("vid_1");

  await assertSucceeds(
    videoRef.set({
      userId: ALICE_UID,
      title: "Showroom Campaign",
      videoUrl: "https://www.w3schools.com/html/mov_bbb.mp4",
      durationSeconds: 30,
      order: 1,
      videoOrientation: "LANDSCAPE",
      isForcedPlay: false,
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(videoRef.get());

  await assertSucceeds(
    videoRef.update({
      title: "Updated Showroom Campaign",
      videoOrientation: "PORTRAIT",
      isForcedPlay: true,
      updatedAt: now,
    })
  );
});

test("Authenticated user cannot read or modify another user's video or device", async () => {
  const now = new Date();
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context
      .firestore()
      .collection("users")
      .doc(BOB_UID)
      .collection("videos")
      .doc("bob_vid")
      .set({
        userId: BOB_UID,
        title: "Bob Video",
        videoUrl: "https://www.w3schools.com/html/mov_bbb.mp4",
        durationSeconds: 30,
        order: 1,
        videoOrientation: "AUTO",
        isForcedPlay: false,
        createdAt: now,
        updatedAt: now,
      });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(
    aliceDb
      .collection("users")
      .doc(BOB_UID)
      .collection("videos")
      .doc("bob_vid")
      .get()
  );
  await assertFails(
    aliceDb
      .collection("users")
      .doc(BOB_UID)
      .collection("videos")
      .doc("bob_vid")
      .delete()
  );
});

test("Shadow field injection or invalid enum is rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date();
  const videoRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("videos")
    .doc("vid_bad");

  // Ghost field injection
  await assertFails(
    videoRef.set({
      userId: ALICE_UID,
      title: "Showroom Campaign",
      videoUrl: "https://www.w3schools.com/html/mov_bbb.mp4",
      durationSeconds: 30,
      order: 1,
      videoOrientation: "LANDSCAPE",
      isForcedPlay: false,
      isAdminHack: true,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Invalid videoOrientation enum
  await assertFails(
    videoRef.set({
      userId: ALICE_UID,
      title: "Showroom Campaign",
      videoUrl: "https://www.w3schools.com/html/mov_bbb.mp4",
      durationSeconds: 30,
      order: 1,
      videoOrientation: "DIAGONAL_INVALID",
      isForcedPlay: false,
      createdAt: now,
      updatedAt: now,
    })
  );
});
