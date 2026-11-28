package com.xlythe.view.camera.test;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Walks the emulator's virtual scene camera through its room, for a test that needs the camera to
 * move.
 *
 * <p>The emulator moves the virtual scene camera and the motion sensors from one physical model,
 * so what the camera sees and what the sensors feel agree, which is what AR tracks by. The model
 * is driven through the emulator's gRPC endpoint on the host, which the emulator reaches at
 * {@value #HOST}. Nothing the tests depend on speaks HTTP/2, so this speaks the least of it that
 * unary gRPC calls need: one connection, a stream for each call, literal headers, and the reply's
 * data frame as the answer. The connection stays open between calls, because a busy emulator is
 * slow to let the guest open a new one to the host.</p>
 *
 * <p>Launch the emulator with {@code -grpc <port> -grpc-use-token}, read the token from its
 * discovery file ({@code grpc.token} in {@code avd/running/pid_<pid>.ini} under the temp
 * directory), and pass both to the instrumentation:
 * {@code -e emulatorGrpcPort <port> -e emulatorGrpcToken <token>}.</p>
 */
final class EmulatorScene implements Closeable {
    /** The host, as the emulator sees it. */
    private static final String HOST = "10.0.2.2";

    /** The instrumentation argument naming the gRPC port. */
    private static final String PORT_ARGUMENT = "emulatorGrpcPort";

    /** The instrumentation argument carrying the gRPC token, if the endpoint wants one. */
    private static final String TOKEN_ARGUMENT = "emulatorGrpcToken";

    /** The service every call goes to. */
    private static final String SERVICE = "/android.emulation.control.EmulatorController/";

    /** How long a call is given, connecting and answering both. */
    private static final int TIMEOUT_MILLIS = 5_000;

    /** {@code PhysicalModelValue.PhysicalType.POSITION}: metres, x right, y up, z backwards. */
    private static final int POSITION = 0;

    /** {@code PhysicalModelValue.PhysicalType.ROTATION}: degrees about x, y and z. */
    private static final int ROTATION = 1;

    /** {@code PhysicalModelValue.PhysicalType.VELOCITY}: metres a second along x, y and z. */
    private static final int VELOCITY = 8;

    /** The HTTP/2 frame carrying a message. */
    private static final int DATA = 0;

    /** The HTTP/2 frame opening a stream. */
    private static final int HEADERS = 1;

    /** The HTTP/2 frame each side opens the connection with, and acknowledges. */
    private static final int SETTINGS = 4;

    /** The HTTP/2 frame a peer checks the connection with, which is answered in kind. */
    private static final int PING = 6;

    /** The HTTP/2 frame closing the connection. */
    private static final int GOAWAY = 7;

    /** The HTTP/2 frame giving the sender room for more data. */
    private static final int WINDOW_UPDATE = 8;

    /** The flag on the last frame of a stream. */
    private static final int END_STREAM = 0x1;

    /** The flag acknowledging a SETTINGS or PING frame. */
    private static final int ACK = 0x1;

    /** The flag saying a HEADERS frame holds every header. */
    private static final int END_HEADERS = 0x4;

    /** The port the emulator's gRPC endpoint listens on, on the host. */
    private final int mPort;

    /** The endpoint's token, or null for an endpoint that asks for none. */
    @Nullable
    private final String mToken;

    /** The connection calls go over, or null before the first call and after a failed one. */
    @Nullable
    private Socket mSocket;

    /** The stream the next call opens: odd and rising, as a client's must be. */
    private int mNextStream;

    private EmulatorScene(int port, @Nullable String token) {
        mPort = port;
        mToken = token;
    }

    /** The scene the instrumentation was told how to reach, or null on a phone or without it. */
    @Nullable
    static EmulatorScene fromArguments() {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String port = arguments.getString(PORT_ARGUMENT);
        if (port == null || !DeviceState.isEmulator()) {
            return null;
        }
        return new EmulatorScene(Integer.parseInt(port), arguments.getString(TOKEN_ARGUMENT));
    }

    /** Glides the camera to a place in the room, in metres from where it started. */
    @WorkerThread
    void moveTo(float x, float y, float z) throws IOException {
        set(POSITION, x, y, z);
    }

    /** Sets the camera moving, in metres a second along each axis of the room, until told again. */
    @WorkerThread
    void moveAt(float x, float y, float z) throws IOException {
        set(VELOCITY, x, y, z);
    }

    /** Glides the camera to face a way, in degrees about each axis of the room. */
    @WorkerThread
    void turnTo(float x, float y, float z) throws IOException {
        set(ROTATION, x, y, z);
    }

    /** Where the camera is now, in metres. */
    @WorkerThread
    @NonNull
    float[] getPosition() throws IOException {
        return floats(call("getPhysicalModel", target(POSITION)));
    }

    @WorkerThread
    private void set(int target, float x, float y, float z) throws IOException {
        ByteBuffer values = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        values.putFloat(x).putFloat(y).putFloat(z);
        ByteArrayOutputStream parameter = new ByteArrayOutputStream();
        // ParameterValue.data, field 1, packed floats.
        parameter.write(0x0a);
        parameter.write(values.capacity());
        parameter.write(values.array(), 0, values.capacity());
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        message.write(target(target));
        // PhysicalModelValue.value, field 3. Interpolation is left SMOOTH, so the camera glides
        // and the sensors feel it accelerate, as a hand-held phone would.
        message.write(0x1a);
        message.write(parameter.size());
        parameter.writeTo(message);
        call("setPhysicalModel", message.toByteArray());
    }

    /** {@code PhysicalModelValue.target}, field 1, left out when it is the default. */
    @NonNull
    private static byte[] target(int target) {
        return target == 0 ? new byte[0] : new byte[] {0x08, (byte) target};
    }

    /** {@code PhysicalModelValue.value.data}, from a reply. */
    @NonNull
    private static float[] floats(@NonNull byte[] reply) throws IOException {
        for (int i = 0; i + 3 < reply.length; ) {
            int tag = reply[i] & 0xff;
            if (tag == 0x1a && reply[i + 2] == 0x0a) {
                int length = reply[i + 3] & 0xff;
                ByteBuffer data = ByteBuffer.wrap(reply, i + 4, length)
                        .order(ByteOrder.LITTLE_ENDIAN);
                float[] values = new float[length / 4];
                for (int j = 0; j < values.length; j++) {
                    values[j] = data.getFloat();
                }
                return values;
            }
            if ((tag & 7) != 0) {
                break;
            }
            // A varint field: the tag, then bytes until one without its high bit.
            i++;
            while (i < reply.length && (reply[i] & 0x80) != 0) {
                i++;
            }
            i++;
        }
        throw new IOException("The emulator described its physical model in a way not understood");
    }

    /**
     * Makes one unary call and returns the reply's message.
     *
     * @throws IOException if the endpoint cannot be reached or does not answer with a message,
     *     which is how a wrong token is refused
     */
    @WorkerThread
    @NonNull
    private synchronized byte[] call(@NonNull String method, @NonNull byte[] message)
            throws IOException {
        try {
            return exchange(method, message);
        } catch (IOException error) {
            // Whatever state the connection was left in, the next call starts a new one.
            close();
            throw error;
        }
    }

    @WorkerThread
    @NonNull
    private byte[] exchange(@NonNull String method, @NonNull byte[] message) throws IOException {
        Socket socket = mSocket;
        if (socket == null) {
            socket = new Socket();
            mSocket = socket;
            mNextStream = 1;
            socket.connect(new InetSocketAddress(HOST, mPort), TIMEOUT_MILLIS);
            socket.setSoTimeout(TIMEOUT_MILLIS);
            socket.getOutputStream().write(
                    "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            writeFrame(socket.getOutputStream(), SETTINGS, 0, 0, new byte[0]);
        }
        int id = mNextStream;
        mNextStream += 2;
        OutputStream out = socket.getOutputStream();
        ByteArrayOutputStream headers = new ByteArrayOutputStream();
        literal(headers, ":method", "POST");
        literal(headers, ":scheme", "http");
        literal(headers, ":path", SERVICE + method);
        literal(headers, ":authority", "localhost");
        literal(headers, "content-type", "application/grpc");
        literal(headers, "te", "trailers");
        if (mToken != null) {
            literal(headers, "authorization", "Bearer " + mToken);
        }
        ByteBuffer body = ByteBuffer.allocate(5 + message.length);
        body.put((byte) 0).putInt(message.length).put(message);
        writeFrame(out, HEADERS, END_HEADERS, id, headers.toByteArray());
        writeFrame(out, DATA, END_STREAM, id, body.array());
        out.flush();

        DataInputStream in = new DataInputStream(socket.getInputStream());
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        boolean answered = false;
        while (true) {
            int length = (in.readUnsignedByte() << 16) | in.readUnsignedShort();
            int type = in.readUnsignedByte();
            int flags = in.readUnsignedByte();
            int stream = in.readInt() & 0x7fffffff;
            byte[] payload = new byte[length];
            in.readFully(payload);
            if (type == SETTINGS && (flags & ACK) == 0) {
                writeFrame(out, SETTINGS, ACK, 0, new byte[0]);
            } else if (type == PING && (flags & ACK) == 0) {
                writeFrame(out, PING, ACK, 0, payload);
            } else if (type == DATA) {
                if (length > 0) {
                    // Gives back the room the reply took, or over a long walk the emulator runs
                    // out of it and stops answering.
                    writeFrame(out, WINDOW_UPDATE, 0, 0,
                            ByteBuffer.allocate(4).putInt(length).array());
                }
                if (stream == id) {
                    reply.write(payload);
                    answered = true;
                }
            } else if (type == GOAWAY) {
                throw new IOException("The emulator closed the connection");
            }
            if (stream == id && (flags & END_STREAM) != 0) {
                break;
            }
        }
        byte[] framed = reply.toByteArray();
        if (!answered || framed.length < 5) {
            throw new IOException("The emulator refused " + method
                    + "; check " + PORT_ARGUMENT + " and " + TOKEN_ARGUMENT);
        }
        byte[] result = new byte[framed.length - 5];
        System.arraycopy(framed, 5, result, 0, result.length);
        return result;
    }

    /** Closes the connection to the emulator, which the next call opens again. */
    @Override
    public synchronized void close() {
        Socket socket = mSocket;
        mSocket = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException error) {
                // Already gone, which is all closing asks.
            }
        }
    }

    private static void writeFrame(@NonNull OutputStream out, int type, int flags, int stream,
            @NonNull byte[] payload) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(9);
        header.put((byte) (payload.length >> 16)).put((byte) (payload.length >> 8))
                .put((byte) payload.length).put((byte) type).put((byte) flags).putInt(stream);
        out.write(header.array());
        out.write(payload);
    }

    /** An HPACK literal header field, never indexed and never Huffman coded. */
    private static void literal(@NonNull ByteArrayOutputStream out, @NonNull String name,
            @NonNull String value) {
        out.write(0x00);
        string(out, name.getBytes(StandardCharsets.US_ASCII));
        string(out, value.getBytes(StandardCharsets.US_ASCII));
    }

    /** An HPACK string: its length as a 7-bit-prefixed integer, then its bytes. */
    private static void string(@NonNull ByteArrayOutputStream out, @NonNull byte[] bytes) {
        int length = bytes.length;
        if (length < 0x7f) {
            out.write(length);
        } else {
            out.write(0x7f);
            length -= 0x7f;
            while (length >= 0x80) {
                out.write((length & 0x7f) | 0x80);
                length >>= 7;
            }
            out.write(length);
        }
        out.write(bytes, 0, bytes.length);
    }
}
