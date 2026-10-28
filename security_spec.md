# Security Specification — SignageTV Real-Time Cloud Database

## 1. Data Invariants
1. **Strict Multi-Tenant Isolation (`/users/{userId}/...`)**: Every video (`/users/{userId}/videos/{videoId}`), device (`/users/{userId}/devices/{deviceId}`), and broadcast config (`/users/{userId}/config/{configId}`) belongs strictly to the authenticated Google user whose `request.auth.uid == userId` and `data.userId == request.auth.uid`.
2. **Immutable Ownership & Creation Metadata**: On `update`, `userId` and `createdAt` must remain strictly equal to `resource.data.userId` and `resource.data.createdAt`.
3. **Path Variable & Field Size Bounds**: Every document ID (`userId`, `videoId`, `deviceId`, `configId`) must match `^[a-zA-Z0-9_\-]+$` with `.size() <= 128`, and every string field is bounded by `.size()` checks.
4. **Server Timestamp Bounds**: `createdAt` and `updatedAt` must be Firestore `timestamp` values (`<= request.time`).

## 2. The "Dirty Dozen" Adversarial Payloads
1. **Unauthenticated Read/Write**: Attempting `get`, `list`, `create`, `update`, or `delete` on `/users/alice_123/videos` with `request.auth == null`.
2. **Cross-User Read (PII / Tenant Leak)**: Authenticated as `bob_456`, attempting to `get` or `list` `/users/alice_123/videos` or `/users/alice_123/devices`.
3. **Identity Spoofing on Create**: Authenticated as `alice_123`, creating `/users/alice_123/videos/vid_1` with `{"userId": "bob_456", ...}`.
4. **Shadow Field Injection ("Ghost Field")**: Sending `{"isVerifiedAdmin": true}` alongside valid fields on `create` or `update`.
5. **Immutable Field Tampering (`createdAt` / `userId` mutation)**: Updating an existing video document while changing `createdAt` or `userId`.
6. **Value Poisoning (Invalid Enum)**: Setting `videoOrientation: "DIAGONAL_HACK"` or `screenOrientation: "INVALID"` on a video or device.
7. **Resource Exhaustion (1MB String Bomb)**: Setting `title` or `videoUrl` to a 10,000-character string.
8. **ID Poisoning**: Creating a document with a malicious ID containing spaces or path characters.
9. **Type Confusion on Numeric Fields**: Passing `"durationSeconds": "60"` (string instead of `int`) or negative duration `0`.
10. **Client Clock Skew / Epoch Long Timestamp**: Passing `"createdAt": 1700000000000` (integer instead of server `timestamp`) or a future timestamp `> request.time`.
11. **Partial Update Deleting Required Key**: Using `FieldValue.delete()` on `title` or `videoUrl` during `update`.
12. **Unauthorized Cross-User Delete**: Authenticated as `bob_456`, attempting to delete `/users/alice_123/videos/vid_1`.
