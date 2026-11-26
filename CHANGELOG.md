# Changelog

## 0.1.0

- Built on hibira-java 0.3.1, which signs Chat API calls over the decoded path, so conversation and user IDs with a space or `/` work.

- First release: `HibiraPlugin` with coroutine access to events, the Chat API and Approvals; `hibiraChannelAuth`, `hibiraUserAuth` and `hibiraWebhooks` routes; the `HibiraWaitingRoom` route plugin; Live Chat verified visitors; `verifyApprovalCallback`.
