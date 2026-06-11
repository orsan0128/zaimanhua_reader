#!/usr/bin/env python3
"""再漫画阅读器 - 后端服务器"""
import http.server
import json
import re
import urllib.request
import urllib.parse
from http.server import HTTPServer, SimpleHTTPRequestHandler

API_BASE = 'https://v4api.zaimanhua.com'
RANK_URL = 'https://www.zaimanhua.com/rank'

def fetch_json(path):
    """Fetch JSON from API"""
    url = API_BASE + path
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    with urllib.request.urlopen(req, timeout=10) as resp:
        return json.loads(resp.read())

def fetch_rank_data():
    """Scrape ranking data from the rank page"""
    req = urllib.request.Request(RANK_URL, headers={'User-Agent': 'Mozilla/5.0'})
    with urllib.request.urlopen(req, timeout=10) as resp:
        html = resp.read().decode('utf-8')
    
    # Extract from __NUXT__ data
    match = re.search(r'__NUXT__=\(function\([^)]*\)\{return \{data:\{rankingList:\{list:\[(.*?)\]', html, re.DOTALL)
    if not match:
        return []
    
    data_str = match.group(1)
    items = []
    
    # Parse each item - num is optional
    item_pattern = re.compile(
        r'\{comic_id:(\d+),title:"([^"]+)".*?authors:"([^"]*)",comic_py:"([^"]*)",types:"([^"]*)",cover:"([^"]*)".*?(?:num:(\d+))?\}',
        re.DOTALL
    )
    
    for m in item_pattern.finditer(data_str):
        items.append({
            'comic_id': int(m.group(1)),
            'title': m.group(2),
            'authors': m.group(3),
            'comic_py': m.group(4),
            'types': m.group(5),
            'cover': m.group(6).replace('\\u002F', '/'),
            'num': int(m.group(7)) if m.group(7) else 0
        })
    
    return items

class Handler(SimpleHTTPRequestHandler):
    def do_GET(self):
        if self.path.startswith('/api/rank'):
            try:
                data = fetch_rank_data()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Access-Control-Allow-Origin', '*')
                self.end_headers()
                self.wfile.write(json.dumps({'errno': 0, 'data': {'list': data}}, ensure_ascii=False).encode())
            except Exception as e:
                self.send_response(500)
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                self.wfile.write(json.dumps({'errno': 1, 'errmsg': str(e)}).encode())
        else:
            super().do_GET()
    
    def log_message(self, format, *args):
        pass  # Suppress logs

if __name__ == '__main__':
    port = 8888
    server = HTTPServer(('0.0.0.0', port), Handler)
    print(f'再漫画阅读器 running at http://localhost:{port}')
    server.serve_forever()
