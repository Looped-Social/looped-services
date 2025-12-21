# **Looped Anonymous Posting — Privacy & Security at a Glance**

**TL;DR:** When you post “Anonymous” on Looped, your post is **not linkable** to your real account—even by us. You can still like, follow, and save content as “Anonymous,” but **no one (including Looped) can see who you are.**

## **What we guarantee**

* **No identity link in our database.** Anonymous posts, comments, likes, follows, and saves don’t store your user ID anywhere.

* **Company/space proof only.** Your device holds a private, anonymous “persona key” and a **membership certificate** (valid \~365 days) that proves you’re verified for a company/space—**without** revealing who you are.

* **No backdoors.** We don’t keep logs or secret mappings that could connect your anonymous persona to your named account.

* **Media is safe too.** Images/videos attached to anonymous content aren’t owned by your account in the database, so they can’t be back-traced to you.

* **Open verification.** We publish our issuer public keys so anyone can verify an anonymous post has a valid certificate—without learning the author.

## **How it works (plain English)**

1. You verify employment (email/HR/LinkedIn/manual).

2. Your **device** creates an anonymous persona key and gets a **blinded** certificate proving access to a company/space for the next 12 months.

3. When you post/like/follow as “Anonymous,” your device proves “member of `<Company/Space>`” with that certificate—**not** your identity.

4. Moderators can remove content or ban a misbehaving **anonymous persona**, but still can’t see who you are.

## **Multi-device (optional)**

Want the **same** “Anonymous” identity on iPhone/iPad/Web? Use **Anonymous Backup**:

* We give you a **Recovery Code** (non-secret locator) and you set a **passphrase** (your secret).

* Your persona key is encrypted on your device and uploaded under the code.

* On another device, enter the **Recovery Code \+ passphrase** to restore the same anonymous identity.

## **Enrollment & Rotation (client)**

**Issuer key**  
Call `GET /anon/issuer` to fetch the issuer **public key PEM** (`public_key_pem`) and `kid`. The client uses this key to blind the certificate request.

**Enroll**  
Use `POST /anon/enroll` with `personaPubkey` and `blindedMessage` to receive `anon_profile_id`, `handle`, `anon_cert_kid`, and `blinded_signature`. Unblind the signature on device and store it as `anon_cert`.

**Rotate anonymous identity (new persona)**  
To create a new anonymous account (one‑per‑user), do:
1) `POST /anon/revoke` with anon proof (no JWT) to revoke the old persona.
2) `POST /anon/reset` (JWT required) to clear enrollment sanction.
3) `POST /anon/enroll` with a **new** persona key + blinded message.

## **What we don’t do**

* We don’t store any link between your named account and your anonymous persona.

* We don’t attach your user ID to anonymous content or media.

* We don’t use idempotency/request keys tied to your account on anonymous endpoints.

## **What you can do to stay safe**

* Keep the app updated.

* Use **Anonymous Backup** if you want the same anon identity across devices.

* Prefer one-off anonymous posts if you want maximum unlinkability even across your own posts.

---
