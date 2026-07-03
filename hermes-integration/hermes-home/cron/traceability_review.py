#!/usr/bin/env python3
"""
Cron Job: Traceability Review
Schedule: Daily at 09:00

Pulls the full traceability matrix from SysON, checks for coverage gaps,
and reports unsatisfied requirements. Output is delivered to the chat
via Hermes cron delivery.

How Hermes cron works:
  1. This script runs on schedule (cron triggers it)
  2. Script calls the MCP bridge directly (bypassing the LLM for speed)
  3. Script stdout becomes the "report" that Hermes formats and delivers
  4. If there are gaps, Hermes can then use MCP tools to investigate further

Usage:
  # Register with Hermes cron:
  hermes cron create "0 9 * * *" --script traceability_review.py --name "Daily Traceability Review"

  # Or run manually:
  python traceability_review.py --project afa126b5-daa8-41f2-9b1e-bae1ecb0d64f
"""

import os
import sys
import json
import argparse
from datetime import datetime

# The MCP bridge URL (internal Docker network)
MCP_URL = os.environ.get("MCP_BRIDGE_URL", "http://mcp-bridge:3001")
SYSON_PROJECT = os.environ.get("SYSON_PROJECT_ID", "")

import urllib.request


def call_mcp(tool: str, arguments: dict) -> dict:
    """Call an MCP tool on the bridge."""
    payload = json.dumps({"tool": tool, "arguments": arguments}).encode()
    req = urllib.request.Request(
        f"{MCP_URL}/mcp",
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode())


def run_review(project_id: str) -> str:
    """Run the traceability review and return a formatted report."""

    # 1. Get coverage summary
    coverage = call_mcp("get_requirements_coverage", {"project_id": project_id})

    # 2. Get full traceability matrix
    matrix = call_mcp("get_traceability_matrix", {"project_id": project_id})

    # 3. Build report
    lines = []
    lines.append(f"╔══════════════════════════════════════════════╗")
    lines.append(f"║  Daily Traceability Review — {datetime.now().strftime('%Y-%m-%d %H:%M')}  ║")
    lines.append(f"╠══════════════════════════════════════════════╣")
    lines.append(f"║  Project: {project_id[:36]}  ║")
    lines.append(f"╠══════════════════════════════════════════════╣")

    c = coverage.get("coverage", coverage)
    total = c.get("total_requirements", 0)
    covered = c.get("covered", 0)
    uncovered = c.get("uncovered", 0)
    pct = (covered / total * 100) if total > 0 else 0

    lines.append(f"║  Requirements: {total:>4} total")
    lines.append(f"║  Covered:      {covered:>4}  ({pct:.0f}%)")
    lines.append(f"║  Uncovered:    {uncovered:>4}  ({100-pct:.0f}%)")
    lines.append(f"║  Parts:        {c.get('total_parts', 0):>4}")
    lines.append(f"║  Dependencies: {matrix.get('matrix', matrix).get('total', 0):>4}")

    # 4. Flag issues
    if uncovered > 0:
        lines.append(f"╠══════════════════════════════════════════════╣")
        lines.append(f"║  ⚠ {uncovered} requirements have NO traceability links.")
        lines.append(f"║  Action needed: review and add dependencies.")

    if total == 0:
        lines.append(f"╠══════════════════════════════════════════════╣")
        lines.append(f"║  ⚠ No requirements found in project.")

    lines.append(f"╚══════════════════════════════════════════════╝")

    return "\n".join(lines)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", default=SYSON_PROJECT, help="Project ID")
    args = parser.parse_args()

    if not args.project:
        print("ERROR: No project ID. Set SYSON_PROJECT_ID or pass --project")
        sys.exit(1)

    report = run_review(args.project)
    print(report)
