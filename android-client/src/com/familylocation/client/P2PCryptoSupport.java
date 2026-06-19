package com.familylocation.client;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

final class P2PCryptoSupport {
    interface JsonTransport {
        JSONObject postJson(String endpoint, JSONObject payload) throws Exception;
    }

    private static final String KEY_PRIVATE_PKCS8 = "p2p_private_pkcs8";
    private static final String KEY_PUBLIC_N = "p2p_public_n";
    private static final String KEY_PUBLIC_E = "p2p_public_e";
    private static final SecureRandom RANDOM = new SecureRandom();

    private P2PCryptoSupport() {
    }

    static JSONObject status(JsonTransport transport, String groupName) throws Exception {
        return transport.postJson("api/p2p_crypto.php", new JSONObject()
            .put("action", "status")
            .put("group_name", groupName));
    }

    static JSONObject publishKey(JsonTransport transport, Context context, String groupName) throws Exception {
        return transport.postJson("api/p2p_crypto.php", new JSONObject()
            .put("action", "publish_key")
            .put("group_name", groupName)
            .put("public_key_jwk", publicJwk(context)));
    }

    static JSONObject setConsent(JsonTransport transport, Context context, String groupName, boolean consent) throws Exception {
        publishKey(transport, context, groupName);
        return transport.postJson("api/p2p_crypto.php", new JSONObject()
            .put("action", "consent")
            .put("group_name", groupName)
            .put("consent", consent));
    }

    static JSONObject enableGroup(JsonTransport transport, Context context, String groupName) throws Exception {
        publishKey(transport, context, groupName);
        JSONObject current = status(transport, groupName);
        if (!current.optBoolean("is_owner", false)) {
            throw new IllegalStateException("只有家庭组管理员可以开启端到端加密。");
        }
        if (current.optBoolean("enabled", false)) {
            throw new IllegalStateException("该家庭组已经开启端到端加密。");
        }
        JSONArray members = current.optJSONArray("members");
        if (members == null || members.length() == 0) {
            throw new IllegalStateException("家庭组成员为空，无法开启端到端加密。");
        }
        for (int index = 0; index < members.length(); index += 1) {
            JSONObject member = members.optJSONObject(index);
            if (member == null || !member.optBoolean("consented", false) || member.optJSONObject("public_key_jwk") == null) {
                throw new IllegalStateException("需要组内所有成员先同意并发布公钥。");
            }
        }
        byte[] rawGroupKey = new byte[32];
        RANDOM.nextBytes(rawGroupKey);
        int keyVersion = (int) (System.currentTimeMillis() / 1000L);
        return transport.postJson("api/p2p_crypto.php", new JSONObject()
            .put("action", "enable_group")
            .put("group_name", groupName)
            .put("key_version", keyVersion)
            .put("wrapped_keys", wrappedKeysForMembers(members, rawGroupKey)));
    }

    static JSONObject distributeGroupKey(JsonTransport transport, Context context, String groupName) throws Exception {
        JSONObject current = status(transport, groupName);
        if (!current.optBoolean("is_owner", false)) {
            throw new IllegalStateException("只有家庭组管理员可以补发端到端加密组密钥。");
        }
        if (!current.optBoolean("enabled", false)) {
            throw new IllegalStateException("当前家庭组尚未开启端到端加密。");
        }
        JSONArray members = current.optJSONArray("members");
        JSONArray targets = new JSONArray();
        if (members != null) {
            for (int index = 0; index < members.length(); index += 1) {
                JSONObject member = members.optJSONObject(index);
                if (member != null
                    && member.optBoolean("needs_key_distribution", false)
                    && member.optBoolean("consented", false)
                    && member.optJSONObject("public_key_jwk") != null) {
                    targets.put(member);
                }
            }
        }
        if (targets.length() == 0) {
            return current;
        }
        byte[] rawGroupKey = groupKey(transport, context, groupName);
        return transport.postJson("api/p2p_crypto.php", new JSONObject()
            .put("action", "distribute_group_key")
            .put("group_name", groupName)
            .put("key_version", current.optInt("key_version", 0))
            .put("wrapped_keys", wrappedKeysForMembers(targets, rawGroupKey)));
    }

    static JSONObject encryptedReportOrNull(JsonTransport transport, Context context, String groupName, JSONObject plainPayload) throws Exception {
        if (groupName == null || groupName.trim().isEmpty()) {
            return null;
        }

        JSONObject current = status(transport, groupName);
        if (!current.optBoolean("enabled", false)) {
            return null;
        }

        String wrappedKey = current.optString("wrapped_group_key", "");
        if (wrappedKey.isEmpty()) {
            throw new IllegalStateException("当前设备没有该家庭组的端到端加密组密钥，请先在设置中同意并让组主补发密钥。");
        }

        byte[] rawGroupKey = unwrapGroupKey(context, wrappedKey);
        JSONObject encryptedPayload = encryptPayload(rawGroupKey, plainPayload);
        return new JSONObject()
            .put("group_name", groupName)
            .put("p2p_key_version", current.optInt("key_version", 0))
            .put("encrypted_payload", encryptedPayload);
    }

    static JSONObject decryptRecord(JsonTransport transport, Context context, String fallbackGroupName, JSONObject record) {
        if (record == null || !"p2p-v1".equals(record.optString("encryption_mode", ""))) {
            return record;
        }

        try {
            String groupName = record.optString("group_name", fallbackGroupName == null ? "" : fallbackGroupName);
            if (groupName.isEmpty()) {
                return new JSONObject(record.toString()).put("encrypted_unreadable", true);
            }

            JSONObject encrypted = encryptedPayloadObject(record);
            byte[] rawGroupKey = groupKey(transport, context, groupName);
            byte[] iv = Base64.decode(encrypted.optString("iv", ""), Base64.DEFAULT);
            byte[] ciphertext = Base64.decode(encrypted.optString("ciphertext", ""), Base64.DEFAULT);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(rawGroupKey, "AES"), new GCMParameterSpec(128, iv));
            JSONObject plaintext = new JSONObject(new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8));
            JSONObject merged = new JSONObject(record.toString());
            java.util.Iterator<String> keys = plaintext.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                merged.put(key, plaintext.opt(key));
            }
            merged.put("p2p_decrypted", true);
            return merged;
        } catch (Exception exception) {
            try {
                return new JSONObject(record.toString()).put("encrypted_unreadable", true);
            } catch (Exception ignored) {
                return record;
            }
        }
    }

    private static JSONObject encryptedPayloadObject(JSONObject record) throws Exception {
        Object value = record.opt("encrypted_payload");
        if (value instanceof JSONObject) {
            return (JSONObject) value;
        }
        return new JSONObject(String.valueOf(value == null ? "" : value));
    }

    private static JSONObject encryptPayload(byte[] rawGroupKey, JSONObject plainPayload) throws Exception {
        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);
        JSONObject plaintext = new JSONObject(plainPayload.toString())
            .put("encrypted_at", String.valueOf(System.currentTimeMillis()));

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(rawGroupKey, "AES"), new GCMParameterSpec(128, iv));
        byte[] ciphertext = cipher.doFinal(plaintext.toString().getBytes(StandardCharsets.UTF_8));

        return new JSONObject()
            .put("v", 1)
            .put("alg", "AES-GCM")
            .put("iv", base64(iv))
            .put("ciphertext", base64(ciphertext));
    }

    private static JSONObject wrappedKeysForMembers(JSONArray members, byte[] rawGroupKey) throws Exception {
        JSONObject wrappedKeys = new JSONObject();
        for (int index = 0; index < members.length(); index += 1) {
            JSONObject member = members.optJSONObject(index);
            if (member == null) {
                continue;
            }
            JSONObject publicJwk = member.optJSONObject("public_key_jwk");
            if (publicJwk == null) {
                continue;
            }
            int userId = member.optInt("user_id", 0);
            if (userId <= 0) {
                continue;
            }
            wrappedKeys.put(String.valueOf(userId), wrapGroupKey(publicJwk, rawGroupKey));
        }
        return wrappedKeys;
    }

    private static String wrapGroupKey(JSONObject publicJwk, byte[] rawGroupKey) throws Exception {
        byte[] modulus = Base64.decode(publicJwk.optString("n", ""), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        byte[] exponent = Base64.decode(publicJwk.optString("e", ""), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        RSAPublicKeySpec spec = new RSAPublicKeySpec(new BigInteger(1, modulus), new BigInteger(1, exponent));
        java.security.PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(spec);
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        cipher.init(
            Cipher.ENCRYPT_MODE,
            publicKey,
            new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)
        );
        return base64(cipher.doFinal(rawGroupKey));
    }

    private static byte[] unwrapGroupKey(Context context, String wrappedKey) throws Exception {
        byte[] encrypted = Base64.decode(wrappedKey, Base64.DEFAULT);
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        cipher.init(
            Cipher.DECRYPT_MODE,
            privateKey(context),
            new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)
        );
        return cipher.doFinal(encrypted);
    }

    private static byte[] groupKey(JsonTransport transport, Context context, String groupName) throws Exception {
        JSONObject current = status(transport, groupName);
        String wrappedKey = current.optString("wrapped_group_key", "");
        if (wrappedKey.isEmpty()) {
            throw new IllegalStateException("当前设备没有该家庭组的端到端加密组密钥。");
        }
        return unwrapGroupKey(context, wrappedKey);
    }

    private static java.security.PrivateKey privateKey(Context context) throws Exception {
        ensureKeyPair(context);
        String encoded = prefs(context).getString(KEY_PRIVATE_PKCS8, "");
        if (encoded == null || encoded.isEmpty()) {
            throw new IllegalStateException("端到端加密私钥不存在。");
        }
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(encoded, Base64.DEFAULT)));
    }

    private static JSONObject publicJwk(Context context) throws Exception {
        ensureKeyPair(context);
        return new JSONObject()
            .put("kty", "RSA")
            .put("key_ops", new org.json.JSONArray().put("encrypt"))
            .put("ext", true)
            .put("n", prefs(context).getString(KEY_PUBLIC_N, ""))
            .put("e", prefs(context).getString(KEY_PUBLIC_E, ""));
    }

    private static void ensureKeyPair(Context context) throws Exception {
        SharedPreferences preferences = prefs(context);
        if (!preferences.getString(KEY_PRIVATE_PKCS8, "").isEmpty()
            && !preferences.getString(KEY_PUBLIC_N, "").isEmpty()
            && !preferences.getString(KEY_PUBLIC_E, "").isEmpty()) {
            return;
        }

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, RANDOM);
        KeyPair pair = generator.generateKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();

        preferences.edit()
            .putString(KEY_PRIVATE_PKCS8, Base64.encodeToString(pair.getPrivate().getEncoded(), Base64.NO_WRAP))
            .putString(KEY_PUBLIC_N, base64Url(unsigned(publicKey.getModulus())))
            .putString(KEY_PUBLIC_E, base64Url(unsigned(publicKey.getPublicExponent())))
            .apply();
    }

    private static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            return Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return bytes;
    }

    private static String base64(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    private static String base64Url(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("family_location", Context.MODE_PRIVATE);
    }
}
