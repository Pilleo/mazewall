import json
import sys
import os
import urllib.request

api_url = os.environ.get('PAPERCLIP_API_URL', '')
api_base = api_url.rstrip('/').replace('/api', '')
task_id = os.environ.get('PAPERCLIP_TASK_ID')
api_key = os.environ.get('PAPERCLIP_API_KEY')

req = urllib.request.Request(f"{api_base}/api/issues/{task_id}/comments")
req.add_header('Authorization', f'Bearer {api_key}')
try:
    with urllib.request.urlopen(req) as response:
        data = json.loads(response.read())
        for comment in data:
            author = "Unknown"
            if comment.get('createdByUserId'):
                author = f"User {comment.get('createdByUserId')}"
            elif comment.get('createdByAgentId'):
                author = f"Agent {comment.get('createdByAgentId')}"
            
            print(f"--- Comment ID: {comment['id']} from {author} at {comment['createdAt']} ---")
            print(comment.get('body', ''))
            print()
except Exception as e:
    print(f"Error: {e}")
