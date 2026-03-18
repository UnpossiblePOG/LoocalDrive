# LoocalDrive
A java + web application which will work as google drive for your local network.

## Use Case
You can use this project to create your own Google Drive-like service only for your local network. Devices need to connect to the same WiFi or network to access the storage. This is ideal for sharing files across devices at home or in the office.

## Prerequisites
You need to install Java to run this project. If you don't have Java installed, follow the instructions for your operating system:

### Windows:
Download and install the latest JDK from the [Oracle Website](https://www.oracle.com/java/technologies/downloads/). Make sure to set the `JAVA_HOME` environment variable.

### macOS:
You can install Java using Homebrew:
```bash
brew install openjdk
```

### Linux (Ubuntu/Debian):
Open terminal and run:
```bash
sudo apt update
sudo apt install default-jdk
```

## How to Start
1. Clone this project on your laptop or PC, which will stay in your home or office. It will act as the server storage.
2. Open a terminal or command prompt in the cloned project directory.
3. Compile the Java server code by running:
   ```bash
   javac Server.java
   ```
4. Start the server by running:
   ```bash
   java Server
   ```
5. Find out the IP address of the machine where the server is running (e.g., using `ipconfig` on Windows or `ifconfig`/`ip a` on Mac/Linux).
6. Open a web browser on a different device that is connected to the same network/WiFi.
7. Enter the IP address and port (if defined, e.g., `http://<IP_ADDRESS>:8000`) into the browser's address bar to access your local drive.

## Customization
Feel free to make changes to the code as per your requirements to add more features, modify the UI, or change the server behavior.
