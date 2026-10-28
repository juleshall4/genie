# Use ChatGPT Chat from Genie

Status checked October 28, 2026.

## Conclusion

Genie cannot currently route recognized text through the user’s regular ChatGPT Chat experience and receive the answer in its popup through a documented OpenAI integration. The documented no-key app integration is Sign in with ChatGPT (SIWC): it sends eligible Responses API requests, and its plan usage counts against ChatGPT Work/Codex usage. OpenAI says ChatGPT Work and Codex share limits and credits. See the [SIWC quickstart](https://developers.openai.com/siwc/quickstart), [Sign in with ChatGPT user guide](https://learn.chatgpt.com/docs/sign-in-with-chatgpt), and [ChatGPT pricing guide](https://learn.chatgpt.com/docs/pricing).

OpenAI documents Chat, Work, and Codex as distinct surfaces, but the sources reviewed do not define a separate Chat quota that a third-party Android app can call. Do not promise that moving requests to Chat would protect Work/Codex usage.

## Available paths

| Path | Can Genie receive the answer in its popup? | Usage and limits |
| --- | --- | --- |
| Current SIWC integration | Yes. Genie sends the request and receives a Responses API result. | Uses eligible ChatGPT plan usage shared with Work/Codex. It does not use the regular Chat UI. |
| Open ChatGPT Chat for the user | No documented callback. A user can continue the conversation in ChatGPT and copy or share the answer back to Genie. | It uses ChatGPT’s user-facing Chat surface. OpenAI’s public docs do not establish that this has a separate, guaranteed allowance from Work/Codex. [Chat is intended for quick questions and conversation](https://learn.chatgpt.com/docs/use-chatgpt). |
| Android share or intent handoff | Not as a supported embedded flow. Genie could test a user-initiated Android text-share handoff if ChatGPT appears as a target on the device, but OpenAI’s public docs do not specify an inbound text intent, prompt deep link, or result callback for the ChatGPT Android app. | Treat it as a manual convenience: the user reviews and sends the text in ChatGPT, then returns the answer to Genie. Device and app behavior is unverified. |
| OpenAI API or ChatKit | Yes, with a programmatic response in Genie. | API usage is billed separately from ChatGPT subscriptions. ChatKit embeds an agent connected to a server integration; OpenAI does not document it as a way to call the user's ChatGPT Chat quota. See the [ChatKit guide](https://developers.openai.com/api/docs/guides/chatkit) and [API pricing guide](https://developers.openai.com/api/docs/guides/rate-limits). |

OpenAI also documents ChatGPT plugin extensions that can send a message into the current ChatGPT conversation. The supported extension surface is ChatGPT itself, with extended message sending documented for desktop and Work on the web; it does not provide an Android Genie-to-Chat request and popup response path. See [Plugin Extensions](https://developers.openai.com/plugins/build/extensions).

## Recommendation

Keep SIWC for automatic answers inside Genie. If the user wants to try regular Chat, add a separate **Open in ChatGPT** share action only as a manual handoff and label it clearly. Verify on-device that ChatGPT accepts shared text before shipping; do not treat it as an automatic fallback or claim it preserves a separate quota.

If Genie must return answers automatically while drawing from an independent budget, offer API billing as an explicit opt-in. This requires a user-provided API key and separate billing, but it gives Genie a programmatic response path without using the ChatGPT plan allowance.
