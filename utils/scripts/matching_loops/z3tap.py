#!/usr/bin/env python3
"""Z3 wrapper for Silicon's --z3Exe that records the prover input with per-query timing.

Environment:
  MLOOP_Z3       real Z3 executable
  MLOOP_TAP_DIR  directory for <pid>.in, <pid>.out and <pid>.args

<pid>.in is the exact input Z3 received. Before every top-level command it holds a line
';@T <unix time>'; at end of input or on SIGTERM it ends with ';@E <unix time>'. It is
flushed after every command, so a SIGKILL loses nothing Z3 was sent.
<pid>.out holds every Z3 output line, from stdout and stderr, prefixed with '<unix time> '.
Silicon merges the prover's stderr into its stdout, and Z3 reports running out of memory on
stderr, so the tap forwards stderr to its own stdout as well.
"""
import json
import os
import signal
import subprocess
import sys
import threading
import time


def depth_change(text, in_string):
    """Paren depth change of one input line, and whether it ends inside a |..| or \"..\" token."""
    change = 0
    for c in text:
        if in_string:
            if c == in_string:
                in_string = None
        elif c in '|"':
            in_string = c
        elif c == ';':
            break
        elif c == '(':
            change += 1
        elif c == ')':
            change -= 1
    return change, in_string


def main():
    directory = os.environ['MLOOP_TAP_DIR']
    tag = str(os.getpid())
    with open(os.path.join(directory, tag + '.args'), 'w') as f:
        json.dump(sys.argv[1:], f)
    z3 = subprocess.Popen([os.environ['MLOOP_Z3']] + sys.argv[1:],
                          stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0)
    fin = open(os.path.join(directory, tag + '.in'), 'w', buffering=1 << 16)
    fout = open(os.path.join(directory, tag + '.out'), 'w', buffering=1)
    lock = threading.RLock()
    finished = []

    def finish(*signalled):
        with lock:
            if finished:
                return
            finished.append(True)
            fin.write(';@E %.6f\n' % time.time())
            fin.close()
        if not signalled:
            # End of input: let Z3 answer what it has, but do not let it outlive Silicon.
            try:
                z3.stdin.close()
                z3.wait(1)
            except (BrokenPipeError, subprocess.TimeoutExpired):
                pass
        if z3.poll() is None:
            z3.kill()
        if signalled:
            sys.exit(0)

    signal.signal(signal.SIGTERM, finish)

    output = threading.Lock()

    def pump(stream):
        for line in iter(stream.readline, b''):
            with output:
                fout.write('%.6f %s' % (time.time(), line.decode(errors='replace')))
                try:
                    sys.stdout.buffer.write(line)
                    sys.stdout.buffer.flush()
                except BrokenPipeError:
                    pass

    readers = [threading.Thread(target=pump, args=(stream,), daemon=True) for stream in (z3.stdout, z3.stderr)]
    for reader in readers:
        reader.start()
    depth, in_string = 0, None
    for line in sys.stdin.buffer:
        text = line.decode(errors='replace')
        with lock:
            if finished:
                break
            if depth == 0 and in_string is None and text.startswith('('):
                fin.write(';@T %.6f\n' % time.time())
            fin.write(text)
            fin.flush()
        change, in_string = depth_change(text, in_string)
        depth = max(depth + change, 0)
        try:
            z3.stdin.write(line)
            z3.stdin.flush()
        except BrokenPipeError:
            break
    finish()
    for reader in readers:
        reader.join(timeout=2)
    fout.close()


if __name__ == '__main__':
    main()
