# AirPods like Normal (ALN) - Bringing Apple-only features to Linux and Android for seamless AirPods functionality!
#
# Copyright (C) 2024 Kavish Devar
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU Affero General Public License as published
# by the Free Software Foundation, either version 3 of the License.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU Affero General Public License for more details.
#
# You should have received a copy of the GNU Affero General Public License
# along with this program. If not, see <https://www.gnu.org/licenses/>.

import logging.handlers
import socket
import threading
import signal
import sys
import logging
from . import Connection
import enums
from aln.Notifications import Notifications
from aln.Notifications.Battery import Battery
import os
import bluetooth
from aln.enums import enums
connection = None

SOCKET_PATH = '/tmp/airpods_daemon.sock'
LOG_FOLDER = '.'
LOG_FILE = os.path.join(LOG_FOLDER, 'airpods_daemon.log')

# Global flag to control the server loop
running = True

# Configure logging to write to a file
# logging.basicConfig(filename=LOG_FILE, level=logging.DEBUG, format='%(asctime)s %(levelname)s : %(message)s')

# RotatingFileHandler

formatter = logging.Formatter('%(asctime)s - %(levelname)s - %(message)s')
handler = logging.handlers.RotatingFileHandler(LOG_FILE, maxBytes=2**20)
handler.setFormatter(formatter)
handler.setLevel(logging.DEBUG)

l = logging.getLogger()
l.setLevel(logging.DEBUG)
l.addHandler(handler)

from json import JSONEncoder

def handle_client(connection, client_socket):
    """Handle client requests by forwarding all received data to aln.Connection, send data back to the client."""

    def send_status():
        while running:
            try:
                for notif_key in list(globals().keys()):
                    if notif_key.startswith("notif_"):
                        data = globals().get(notif_key)
                        if data:
                            if notif_key == "notif_battery":
                                data: list[Battery] = data
                                batteryJSON = {"type": "battery"}
                                for i in data:
                                    batteryJSON[i.get_component()] = {
                                        "status": i.get_status(),
                                        "level": i.get_level()
                                    }
                                data: str = JSONEncoder().encode(batteryJSON)

                            elif notif_key == "notif_ear_detection":
                                # noinspection PyTypeChecker
                                data: list[int] = data
                                earDetectionJSON = {
                                    "type": "ear_detection",
                                    "primary": data[0],
                                    "secondary": data[1]
                                }
                                data: str = JSONEncoder().encode(earDetectionJSON)
                            elif notif_key == "notif_anc":
                                data: int = data
                                ancJSON = {
                                    "type": "anc",
                                    "mode": data,
                                }
                                data: str = JSONEncoder().encode(ancJSON)
                            elif notif_key == "notif_ca":
                                data: int = data
                                caJSON = {
                                    "type": "ca",
                                    "status": data,
                                }
                                data: str = JSONEncoder().encode(caJSON)
                            elif notif_key == "notif_unknown":
                                logging.debug(f"Unhandled notification type: {notif_key}")
                                logging.debug(f"Data: {data}")
                                data: str = JSONEncoder().encode({"type": "unknown", "data": data})
                            if not client_socket or not isinstance(client_socket, socket.socket):
                                logging.error("Invalid client socket")
                                break
                            logging.info(f'Sending {notif_key} status: {data}')
                            client_socket.sendall(data.encode('utf-8'))
                            logging.info(f'Sent {notif_key} status: {data}')
                            globals()[notif_key] = None
            except socket.error as e:
                logging.error(f"Socket error sending status: {e}")
                break
            except Exception as e:
                logging.error(f"Error sending status: {e}")
                break

    def receive_commands():
        while running:
            try:
                data = client_socket.recv(1024)
                if not data:
                    break
                logging.info(f'Received command: {data}')
                connection.send(data)
            except Exception as e:
                logging.error(f"Error receiving command: {e}")
                break

    # Start two threads to handle sending and receiving data
    send_thread = threading.Thread(target=send_status)
    send_thread.start()
    receive_thread = threading.Thread(target=receive_commands)
    receive_thread.start()

    send_thread.join()
    receive_thread.join()

    client_socket.close()
    logging.info("Client socket closed")

def start_socket_server(connection):
    """Start a UNIX domain socket server."""
    global running
    
    # Set up the socket
    server_socket = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        server_socket.bind(SOCKET_PATH)
    except OSError:
        logging.error(f"Socket already in use or unavailable: {SOCKET_PATH}")
        sys.exit(1)

    server_socket.listen(1)
    logging.info(f"Socket server listening on {SOCKET_PATH}")

    while running:
        try:
            client_socket, _ = server_socket.accept()
            logging.info("Client connected")

            # Handle the client connection in a separate thread
            client_thread = threading.Thread(target=handle_client, args=(connection, client_socket))
            client_thread.start()
        except Exception as e:
            logging.error(f"Error accepting connection: {e}")

    # Close the server socket when stopped
    server_socket.close()
    logging.info("Socket server stopped")

def stop_daemon(_, __):
    """Signal handler to stop the daemon."""
    global running
    logging.info("Received termination signal. Stopping daemon...")
    running = False  # Set running flag to False to stop the loop

    # Close the socket gracefully by removing the file path
    try:
        socket.socket(socket.AF_UNIX, socket.SOCK_STREAM).connect(SOCKET_PATH)
    except socket.error:
        pass
    finally:
        # Remove the socket file
        if os.path.exists(SOCKET_PATH):
            os.remove(SOCKET_PATH)

    sys.exit(0)

def notification_handler(notification_type: int, data: bytes):
    global connection

    logging.debug(f"Received notification: {notification_type}")
    if notification_type == Notifications.BATTERY_UPDATED:
        logger = logging.getLogger("Battery Status")
        battery = connection.notificationListener.BatteryNotification.getBattery()
        globals()["notif_battery"] = battery
        for i in battery:
            logger.debug(f'{i.get_component()} - {i.get_status()}: {i.get_level()}')
    elif notification_type == Notifications.EAR_DETECTION_UPDATED:
        logger = logging.getLogger("In-Ear Status")
        earDetection = connection.notificationListener.EarDetectionNotification.getEarDetection()
        globals()["notif_ear_detection"] = earDetection
        logger.debug(earDetection)
    elif notification_type == Notifications.ANC_UPDATED:
        logger = logging.getLogger("ANC Status")
        anc = connection.notificationListener.ANCNotification.status
        globals()["notif_anc"] = anc
        logger.debug(anc)
    elif notification_type == Notifications.CA_UPDATED:
        logger = logging.getLogger("Conversational Awareness Status")
        ca = connection.notificationListener.ConversationalAwarenessNotification.status
        globals()["notif_ca"] = ca
        logger.debug(ca)
    elif notification_type == Notifications.UNKNOWN:
        logger = logging.getLogger("Unknown Notification")
        hex_data = ' '.join(f'{byte:02x}' for byte in data)
        globals()["notif_unknown"] = hex_data
        logger.debug(hex_data)

def main():
    global running
    logging.info("Starting AirPods daemon")
    
    connection = Connection(mac)
    globals()['connection'] = connection

    # Connect to the AirPods and send the handshake
    try: 
        connection.connect()
    except bluetooth.btcommon.BluetoothError as e:
        logging.error(f"Failed to connect to {mac}: {e}")
        sys.exit(1)
    
    connection.send(enums.HANDSHAKE)
    logging.info("Handshake sent")
    
    connection.initialize_notifications(notification_handler)

    # Start the socket server to listen for client connections
    start_socket_server(connection)

    # Set up signal handlers to handle termination signals
    signal.signal(signal.SIGINT, stop_daemon)  # Handle Ctrl+C
    signal.signal(signal.SIGTERM, stop_daemon)  # Handle kill signal

if __name__ == "__main__":
    # Daemonize the process
    if os.fork():
        sys.exit()

    os.setsid()

    if os.fork():
        sys.exit()

    sys.stdout.flush()
    sys.stderr.flush()

    with open('/dev/null', 'r') as devnull:
        os.dup2(devnull.fileno(), sys.stdin.fileno())

    with open(LOG_FILE, 'a+') as logfile:
        os.dup2(logfile.fileno(), sys.stdout.fileno())
        os.dup2(logfile.fileno(), sys.stderr.fileno())

    main()
