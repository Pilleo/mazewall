import os
import json
import logging
import asyncio
from datetime import datetime, timezone
import httpx

# Configuration
PAPERCLIP_API_URL = os.getenv("PAPERCLIP_API_URL", "http://127.0.0.1:3100")
PAPERCLIP_API_KEY = os.getenv("PAPERCLIP_API_KEY")
PAPERCLIP_COMPANY_ID = os.getenv("PAPERCLIP_COMPANY_ID")
TELEGRAM_BOT_TOKEN = os.getenv("TELEGRAM_BOT_TOKEN")
TELEGRAM_CHAT_ID = os.getenv("TELEGRAM_CHAT_ID")

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

class TelegramBridge:
    def __init__(self):
        self.api_url = PAPERCLIP_API_URL.rstrip('/')
        self.api_key = PAPERCLIP_API_KEY
        self.company_id = PAPERCLIP_COMPANY_ID
        self.tg_token = TELEGRAM_BOT_TOKEN
        self.tg_chat_id = TELEGRAM_CHAT_ID
        self.last_timestamp = datetime.now(timezone.utc).isoformat()
        self.last_update_id = 0

    async def tg_request(self, method: str, data: dict = None):
        if not self.tg_token:
            logger.warning(f"Telegram not configured. Would send {method}: {data}")
            return {}
        url = f"https://api.telegram.org/bot{self.tg_token}/{method}"
        async with httpx.AsyncClient() as client:
            try:
                resp = await client.post(url, json=data)
                resp.raise_for_status()
                return resp.json()
            except Exception as e:
                logger.error(f"Telegram request failed: {e}")
                return {}

    async def send_message(self, text, reply_markup=None):
        data = {"chat_id": self.tg_chat_id, "text": text, "parse_mode": "HTML"}
        if reply_markup:
            data["reply_markup"] = reply_markup
        await self.tg_request("sendMessage", data)

    async def process_event(self, event):
        action = event.get("action")
        if not action:
            return

        evt_ts = event.get("createdAt")
        if evt_ts and evt_ts > self.last_timestamp:
            self.last_timestamp = evt_ts

        details = event.get("details", {})
        entity_id = event.get("entityId")

        # Approval Requested
        # Forwards approval_requested with inline [Approve]/[Reject] buttons
        if action in ["approval.requested", "approval_requested"]:
            approval_id = entity_id
            target = details.get("target", "Unknown")
            text = f"<b>Approval Requested</b>\n\nAction: {action}\nActor: {event.get('actorId', 'Unknown')}\nTarget: {target}"
            reply_markup = {
                "inline_keyboard": [[
                    {"text": "Approve", "callback_data": f"approve:{approval_id}"},
                    {"text": "Reject", "callback_data": f"reject:{approval_id}"}
                ]]
            }
            await self.send_message(text, reply_markup)

        # Issue Status Changed
        # Forwards issue_status_changed and run_failed alerts
        elif action in ["issue.status_changed", "issue_status_changed", "issue.updated"]:
            status = details.get("status") or event.get("status")
            prev_status = details.get("previousStatus")
            # Only alert if status actually changed or the action explicitly says status changed
            if "status_changed" in action or (status and status != prev_status):
                text = f"<b>Issue Status Changed</b>\n\nIssue: {details.get('identifier', entity_id)}\nNew Status: {status}"
                await self.send_message(text)

        # Run Failed
        elif action in ["run.failed", "run_failed"] or (action == "environment.lease_released" and details.get("status") == "failed"):
            run_id = event.get("runId", event.get("entityId", "Unknown"))
            reason = details.get("failureReason", "Unknown")
            issue_id = details.get("issueId", "Unknown")
            text = f"<b>Run Failed</b>\n\nIssue: {issue_id}\nRun: {run_id}\nReason: {reason}"
            await self.send_message(text)

    async def poll_telegram(self):
        while True:
            if not self.tg_token:
                await asyncio.sleep(5)
                continue
            try:
                data = {"offset": self.last_update_id + 1, "timeout": 10}
                resp = await self.tg_request("getUpdates", data)
                updates = resp.get("result", [])
                for update in updates:
                    self.last_update_id = update["update_id"]
                    if "callback_query" in update:
                        await self.handle_callback(update["callback_query"])
            except Exception as e:
                logger.error(f"Error polling Telegram: {e}")
                await asyncio.sleep(2)
            await asyncio.sleep(1)

    async def handle_callback(self, cb):
        cb_id = cb["id"]
        data = cb.get("data", "")
        if data.startswith("approve:") or data.startswith("reject:"):
            action, approval_id = data.split(":", 1)
            # Routes button callbacks to POST /api/approvals/:id/approve (or /reject)
            url = f"{self.api_url}/api/approvals/{approval_id}/{action}"
            headers = {"Authorization": f"Bearer {self.api_key}"}
            
            async with httpx.AsyncClient() as client:
                try:
                    # Execute API request
                    api_resp = await client.post(url, headers=headers)
                    api_resp.raise_for_status()
                    answer_text = f"Successfully {action}d"
                except Exception as e:
                    logger.error(f"Approval action failed: {e}")
                    answer_text = f"Failed to {action}"

            # Answer callback query to stop loading state on TG UI
            await self.tg_request("answerCallbackQuery", {"callback_query_id": cb_id, "text": answer_text})
            
            # Disable buttons
            msg = cb.get("message")
            if msg:
                await self.tg_request("editMessageReplyMarkup", {
                    "chat_id": msg["chat"]["id"],
                    "message_id": msg["message_id"],
                    "reply_markup": {"inline_keyboard": []}
                })

    async def stream_paperclip_activity(self):
        while True:
            url = f"{self.api_url}/api/companies/{self.company_id}/activity"
            headers = {
                "Authorization": f"Bearer {self.api_key}",
                "Accept": "text/event-stream"
            }
            # High-water mark timestamp fallback
            params = {"since": self.last_timestamp} if self.last_timestamp else {}
            
            try:
                async with httpx.AsyncClient(timeout=None) as client:
                    async with client.stream("GET", url, headers=headers, params=params) as response:
                        response.raise_for_status()
                        content_type = response.headers.get("content-type", "")
                        
                        if "application/json" in content_type:
                            data = await response.aread()
                            text = data.decode("utf-8")
                            if text:
                                events = json.loads(text)
                                if isinstance(events, list):
                                    # Process oldest to newest
                                    events.reverse()
                                    for event in events:
                                        ts = event.get("createdAt", "")
                                        if ts > self.last_timestamp:
                                            await self.process_event(event)
                            await asyncio.sleep(2)
                        else:
                            # Handle actual SSE Server-Sent Events stream
                            async for line in response.aiter_lines():
                                if line.startswith("data:"):
                                    data_text = line[5:].strip()
                                    if data_text:
                                        try:
                                            event = json.loads(data_text)
                                            await self.process_event(event)
                                        except Exception as e:
                                            logger.error(f"Error parsing SSE event: {e}")
            except httpx.ReadTimeout:
                logger.info("Activity stream read timeout, reconnecting...")
            except httpx.HTTPError as e:
                logger.error(f"Activity stream HTTP error: {e}")
                await asyncio.sleep(5)
            except Exception as e:
                logger.error(f"Activity stream unhandled error: {e}")
                await asyncio.sleep(5)

    async def run(self):
        logger.info("Starting Paperclip Telegram Bridge...")
        await asyncio.gather(
            self.poll_telegram(),
            self.stream_paperclip_activity()
        )

if __name__ == "__main__":
    bridge = TelegramBridge()
    asyncio.run(bridge.run())
