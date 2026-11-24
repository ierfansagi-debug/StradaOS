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

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
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

test("Unauthenticated driver cannot read or write road alerts", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("road_alerts").where("status", "==", "active").get());
  await assertFails(
    unauthDb.collection("road_alerts").doc("alert_1").set({
      userId: ALICE_UID,
      category: "police",
      subtype: "roadblock",
      lat: 3.1390,
      lon: 101.6869,
      confirmCount: 1,
      dismissCount: 0,
      status: "active",
      createdAt: new Date(Date.now() - 1000),
      updatedAt: new Date(Date.now() - 1000),
    })
  );
});

test("Authenticated driver can create, query active alerts, and vote cleanly", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = new Date(Date.now() - 5000);

  await assertSucceeds(
    aliceDb.collection("road_alerts").doc("alert_my_1").set({
      userId: ALICE_UID,
      category: "police",
      subtype: "roadblock",
      street: "Jalan Tun Razak",
      description: "Police roadblock",
      country: "MY",
      lat: 3.1578,
      lon: 101.7210,
      confirmCount: 1,
      dismissCount: 0,
      status: "active",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Mirrors Android listener query
  await assertSucceeds(
    bobDb.collection("road_alerts").where("status", "==", "active").limit(120).get()
  );

  // Peer driver vote subcollection + counter increment
  await assertSucceeds(
    bobDb.collection("road_alerts").doc("alert_my_1").collection("votes").doc(BOB_UID).set({
      userId: BOB_UID,
      vote: "confirm",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  await assertSucceeds(
    bobDb.collection("road_alerts").doc("alert_my_1").update({
      confirmCount: 2,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject forged userId, invalid category, unexpected id key, or future timestamp
  await assertFails(
    bobDb.collection("road_alerts").doc("alert_bad_1").set({
      userId: ALICE_UID,
      category: "police",
      subtype: "roadblock",
      lat: 3.14,
      lon: 101.68,
      confirmCount: 1,
      dismissCount: 0,
      status: "active",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertFails(
    aliceDb.collection("road_alerts").doc("alert_bad_2").set({
      id: "alert_bad_2",
      userId: ALICE_UID,
      category: "police",
      subtype: "roadblock",
      lat: 3.14,
      lon: 101.68,
      confirmCount: 1,
      dismissCount: 0,
      status: "active",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertFails(
    bobDb.collection("road_alerts").doc("alert_my_1").update({
      lat: 4.0,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  await assertFails(
    bobDb.collection("road_alerts").doc("alert_my_1").delete()
  );
});
