# Connect Genie to ChatGPT

Genie uses the documented [Sign in with ChatGPT flow for open-source clients](https://developers.openai.com/siwc/token-sharing-open-source/sign-in). It requires an eligible account and client. It does not use an API key or another app's credentials.

## Sign in

1. Open Genie and select **Continue with ChatGPT**, or speak a question and select that button in the popup.
2. Complete sign-in in the emulator's browser. Approve ChatGPT plan usage separately from identity access.
3. On the local callback page, select **Return to Genie**.
4. Dismiss the first-use explanation of ChatGPT plan usage.

The browser must run on the Android device or emulator. The callback listens only on that device's `127.0.0.1` interface. A sign-in attempt expires after five minutes. Cancel or retry in Genie if an attempt is interrupted.

Genie's settings list saved account registrations, support adding or selecting an account, and provide sign-out and **Manage usage** actions. Signing out retains the account's client registration for reauthorization. If remote session revocation cannot be confirmed, Genie reports it so you can disconnect the app in ChatGPT settings.

## Ask a question

Press Space, speak, and wait for transcription to finish. Genie sends only the final transcript to OpenAI, then streams the answer into a scrollable popup. Select **Listen again** to ask a follow-up using the same popup's conversation context.

Wait for **Listening…** before speaking. If the microphone does not become ready within five seconds, or the speech service reports that it is busy or disconnects before speech begins, Genie retries startup once. If startup still fails, the popup shows a retry button.

While answering, Genie uses the selected ChatGPT model to add capitalization and punctuation to the final transcript. This separate request uses your ChatGPT plan and runs independently of the answer. Formatting does not use web search or the assistant's personality. Genie keeps the original transcript if formatting fails, times out, or changes its words. Live text can remain unformatted while you speak.

Closing the popup cancels recognition and any active response request and clears the popup's conversation. It does not undo usage already incurred. Transcripts and answers are not persisted. The connection does not provide access to existing ChatGPT conversations or memory.

Genie fetches the signed-in account's model catalog. In settings, choose a model and its reasoning level. **Automatic** uses the first visible model in the server's order, and **Model default** uses that model's advertised default reasoning level. Choices are saved separately for each account, with reasoning preferences retained per model. Only documented direct Responses API efforts advertised by that model appear; `ultra` is omitted because direct API support has not been verified.

Changes apply to subsequent requests. If a saved model or reasoning level becomes unavailable, Genie asks you to choose again rather than silently substituting another model. **Refresh available models** retries catalog loading. A request requires a current catalog, so an unavailable network leaves the choices disabled until loading succeeds.

Requests use the public Responses API, preferentially over a WebSocket prepared while the user speaks. Each turn sends its conversation context explicitly with `store: false`. HTTP fallback also sets `stream: true`; that HTTP-specific flag is omitted on WebSocket messages. Genie falls back only before inference has been submitted, and closes the connection when the popup closes or the account changes. Genie reports interrupted or failed responses, including failures that arrive after partial text. Partial text is not treated as a completed answer.

## Credential storage

Each account registration retains its issued client ID and verified identity. OAuth uses fresh state, nonce, and PKCE values; ID tokens are signature-verified against OpenAI's published keys and checked for issuer, audience, expiry, and nonce. Refreshes are serialized, and rotating credentials are replaced together.

Credentials are encrypted with an Android Keystore AES-GCM key and written atomically inside the app's private, non-backed-up directory. Tokens are not logged or committed. The stable host ID is stored separately and reused across sign-ins.

An in-progress credential renewal finishes even if you close the popup or switch accounts. Genie saves the replacement credentials without changing the selected account. A late renewal cannot restore credentials after sign-out.

If OpenAI rejects the saved renewal token as unusable, Genie clears that registration's tokens and requests sign-in again with its existing client ID. Temporary server failures retain the saved credentials.

## Validate and install

```sh
./gradlew testDebugUnitTest lintDebug installDebug
```

Unit tests cover callback state and client matching, declined consent, ID-token verification, incremental answers, interrupted streams, and usage-limit errors during streaming. A live answer additionally requires completing sign-in on the emulator.
