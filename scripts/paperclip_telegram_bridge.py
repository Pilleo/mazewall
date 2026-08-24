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


    async def sync_git_lifecycle(self, identifier):
        import glob
        import os
        import re
        import shutil
        import subprocess

        logger.info(f"Syncing git lifecycle for completed issue {identifier}")
        # Fetch issue metadata
        url = f"{self.api_url}/api/issues/{identifier}"
        headers = {"Authorization": f"Bearer {self.api_key}"}
        async with httpx.AsyncClient() as client:
            try:
                resp = await client.get(url, headers=headers)
                resp.raise_for_status()
                issue = resp.json()
            except Exception as e:
                logger.error(f"Failed to fetch issue {identifier}: {e}")
                return

        metadata = issue.get("metadata", {}) or {}
        backlog_file = metadata.get("backlogFile")

        if not backlog_file:
            # Paperclip drops `metadata` on POST/PATCH, so ingested issues carry
            # their linkage in a description marker instead (see
            # paperclip_backlog_sync.kts). This fallback is the primary path for
            # markdown-backlog issues, not just a convenience.
            m = re.search(r"mazewall:backlog-file=(\S+)", issue.get("description") or "")
            if m:
                backlog_file = m.group(1)

        # A marker recorded relative to a different working tree (e.g. an agent
        # pushed from a sandbox dir) can escape this repository. Re-anchor by
        # basename inside the real backlog tree; never move files from outside.
        if backlog_file and (not os.path.isfile(backlog_file) or
                             os.path.relpath(os.path.realpath(backlog_file)).startswith("..")):
            candidates = glob.glob(
                f"docs/internals/backlog/**/{os.path.basename(backlog_file)}", recursive=True)
            backlog_file = candidates[0] if candidates else None

        if not backlog_file:
            backlog_id = metadata.get("backlog_id")
            if backlog_id:
                files = glob.glob(f"docs/internals/backlog/**/issue*{backlog_id}*.md", recursive=True)
                if files:
                    backlog_file = files[0]
            if not backlog_file:
                # Try finding just by identifier
                files = glob.glob(f"docs/internals/backlog/**/issue*{identifier.replace('MAZ-', '')}*.md", recursive=True)
                if files:
                    backlog_file = files[0]

        if not backlog_file or not os.path.exists(backlog_file):
            logger.error(f"backlogFile not found for issue {identifier}")
            return
            
        try:
            # 2. Updates frontmatter to status: resolved
            with open(backlog_file, "r") as f:
                file_content = f.read()
                
            updated_content = re.sub(r"status:\s*[\'\"]?(?:open|in_progress)[\'\"]?", "status: \"resolved\"", file_content, count=1)
            
            with open(backlog_file, "w") as f:
                f.write(updated_content)
                
            # 3. Moves markdown file to docs/internals/backlog/resolved/
            resolved_dir = "docs/internals/backlog/resolved"
            os.makedirs(resolved_dir, exist_ok=True)
            dest = os.path.join(resolved_dir, os.path.basename(backlog_file))
            shutil.move(backlog_file, dest)
            
            # 4. Runs git pull --rebase, stages, and commits
            # 5. On merge conflict, aborts rebase and alerts Telegram.
            subprocess.run(["git", "pull", "--rebase", "--autostash"], cwd=".", check=False)

            git_status = subprocess.run(["git", "status", "--porcelain"], capture_output=True, text=True)
            if "UU" in git_status.stdout:
                subprocess.run(["git", "rebase", "--abort"], cwd=".", check=False)
                await self.send_message(f"🚨 <b>Merge Conflict!</b>\n\nFailed to sync Resolution lifecycle for {identifier}. Rebase aborted. Please reconcile manually.")


                return

            # Stage the whole backlog tree: the source path no longer exists
            # after the move, so `git add <old-path>` would fail with a
            # pathspec error and silently stage nothing.
            subprocess.run(["git", "add", "-A", resolved_dir], cwd=".", check=False)
            subprocess.run(["git", "commit", "-m", f"Resolve {identifier}"], cwd=".", check=False)
            logger.info(f"Successfully synced git lifecycle for {identifier}")
            
        except Exception as e:
            logger.error(f"Failed to process git lifecycle for {identifier}: {e}")

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
                
                if status == "done":
                    await self.sync_git_lifecycle(details.get("identifier", entity_id))


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

    async def resolve_company_id(self):
        # Same contract as paperclip_backlog_sync.kts / run_paperclip_loop.sh:
        # explicit env wins, else auto-detect from the API.
        import httpx as _httpx
        async with _httpx.AsyncClient(timeout=10) as client:
            resp = await client.get(
                f"{self.api_url}/api/companies",
                headers={"Authorization": f"Bearer {self.api_key}"},
            )
            resp.raise_for_status()
            companies = resp.json()
            if not companies:
                raise RuntimeError("No Paperclip company found for bridge auto-detect")
            self.company_id = companies[0]["id"]
            logger.info(f"Auto-detected company id {self.company_id}")

    async def run(self):
        logger.info("Starting Paperclip Telegram Bridge...")
        if not self.company_id:
            await self.resolve_company_id()
        await asyncio.gather(
            self.poll_telegram(),
            self.stream_paperclip_activity()
        )

if __name__ == "__main__":
    bridge = TelegramBridge()
    asyncio.run(bridge.run())
