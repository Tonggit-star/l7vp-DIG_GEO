#!/usr/bin/env python3
"""Deploy L7VP to ARM64 (Kunpeng) server and build Docker images."""

import paramiko
import os
import sys
import time
import tarfile
import io

HOST = "120.46.220.42"
PORT = 22
USER = "root"
PASSWORD = "Labuse123123"
REMOTE_DIR = "/root/l7vp-deploy"

def ssh_connect():
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, PORT, USER, PASSWORD)
    return client

def run_cmd(client, cmd, desc=""):
    if desc:
        print(f"\n>>> {desc}")
    print(f"$ {cmd}")
    stdin, stdout, stderr = client.exec_command(cmd)
    out = stdout.read().decode()
    err = stderr.read().decode()
    if out:
        print(out.strip())
    if err:
        print(f"STDERR: {err.strip()}")
    return out, err

def upload_dir(client, local_dir, remote_dir):
    """Upload directory via SFTP"""
    sftp = client.open_sftp()
    try:
        sftp.mkdir(remote_dir)
    except:
        pass

    for root, dirs, files in os.walk(local_dir):
        rel_path = os.path.relpath(root, local_dir)
        remote_path = os.path.join(remote_dir, rel_path).replace("\\", "/")

        for d in dirs:
            try:
                sftp.mkdir(os.path.join(remote_path, d).replace("\\", "/"))
            except:
                pass

        for f in files:
            local_file = os.path.join(root, f).replace("\\", "/")
            remote_file = os.path.join(remote_path, f).replace("\\", "/")
            file_size = os.path.getsize(local_file)
            if file_size > 1024 * 1024:
                print(f"  Uploading {rel_path}/{f} ({file_size / 1024 / 1024:.1f} MB)...")
            sftp.put(local_file, remote_file)

    sftp.close()
    print(f"\nUpload complete: {remote_dir}")

def main():
    print("=" * 60)
    print("L7VP ARM64 Docker Deployment")
    print("=" * 60)

    # 1. Connect
    print("\n[1/6] Connecting to server...")
    client = ssh_connect()
    print("Connected OK")

    # 2. Check environment
    print("\n[2/6] Checking server environment...")
    run_cmd(client, "uname -m", "Architecture")
    run_cmd(client, "cat /etc/os-release | head -3", "OS")
    run_cmd(client, "java -version 2>&1 || echo 'Java NOT installed'", "Java")
    run_cmd(client, "docker --version 2>&1 || echo 'Docker NOT installed'", "Docker")
    run_cmd(client, "docker-compose --version 2>&1 || docker compose version 2>&1 || echo 'Docker Compose NOT installed'", "Docker Compose")

    # 3. Create remote directory
    print("\n[3/6] Creating remote directory...")
    run_cmd(client, f"rm -rf {REMOTE_DIR} && mkdir -p {REMOTE_DIR}")

    # 4. Upload deploy/docker directory
    print("\n[4/6] Uploading deploy package...")
    local_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "deploy", "docker")
    local_dir = os.path.normpath(local_dir).replace("\\", "/")
    print(f"Local: {local_dir}")
    upload_dir(client, local_dir, REMOTE_DIR)

    # 5. Build Docker images
    print("\n[5/6] Building Docker images...")
    run_cmd(client, f"cd {REMOTE_DIR} && docker build -t l7vp-backend:latest -f backend/Dockerfile backend/", "Build backend image")
    run_cmd(client, f"cd {REMOTE_DIR} && docker build -t l7vp-frontend:latest -f frontend/Dockerfile frontend/", "Build frontend image")

    # 6. Export images
    print("\n[6/6] Exporting Docker images...")
    run_cmd(client, f"cd {REMOTE_DIR} && docker save l7vp-backend:latest l7vp-frontend:latest -o l7vp-images-arm64.tar", "Export images")
    run_cmd(client, f"ls -lh {REMOTE_DIR}/l7vp-images-arm64.tar", "Image size")

    # Show results
    print("\n" + "=" * 60)
    print("DEPLOYMENT COMPLETE!")
    print("=" * 60)
    print(f"\nServer: {HOST}")
    print(f"Images: {REMOTE_DIR}/l7vp-images-arm64.tar")
    print(f"Docker Compose: {REMOTE_DIR}/docker-compose.yml")
    print("\nOn the target ARM server:")
    print(f"  1. Copy deploy directory")
    print(f"  2. docker load -i {REMOTE_DIR}/l7vp-images-arm64.tar")
    print(f"  3. cd {REMOTE_DIR} && docker-compose up -d")

    client.close()

if __name__ == "__main__":
    main()
