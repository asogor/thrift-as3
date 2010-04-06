package org.apache.thrift.transport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TFullDuplexHttpClient extends TTransport {
	private Socket socket = null;
	private final String host;
	private final int port;
	private final String method;
	private final String resource;
	private boolean isOpen = false;
	private boolean stripped = false;
	private ByteArrayOutputStream obuffer = new ByteArrayOutputStream();
	private InputStream in;
	private OutputStream out;
	private int bytesInChunk = 0;
	static private final byte[] CRLF = new byte[] { (byte) 13, (byte) 10 };
	private static final Logger LOGGER = LoggerFactory
			.getLogger(TFullDuplexHttpClient.class.getName());

	@Override
	public void close() {
		try {
			in.close();
			in = null;
			out.close();
			out = null;
			this.isOpen = false;
			this.stripped = false;
		} catch (Exception e) {
			LOGGER.warn("failed to close", e);
		}
	}

	@Override
	public boolean isOpen() {
		return isOpen;
	}

	@Override
	public int read(byte[] buf, int off, int len) throws TTransportException {
		int n1 = 0, n2 = 0, n3 = 0, n4 = 0, cidx = 0;
		byte chunkSize[] = new byte[20];

		try {
			while (!stripped) {
				n1 = n2;
				n2 = n3;
				n3 = n4;
				n4 = in.read();
				if (n2 == -1) {
					return 0;
				}
				if ((n1 == 13) && (n2 == 10) && (n3 == 13) && (n4 == 10)) {
					stripped = true;
				}
			}

			if (bytesInChunk == 0) {
				n1 = -1;
				n2 = -1;

				while (!((n1 == 13) && (n2 == 10))) {
					if (n1 > 0) {
						chunkSize[cidx] = (byte) n1;
						cidx++;
					}
					n1 = n2;
					n2 = in.read();
				}
				bytesInChunk = Integer.parseInt(new String(chunkSize, 0, cidx),
						16);
				// check for closing empty chunk
				if (bytesInChunk == 0) {
					this.isOpen = false;
					this.out.close();
					this.in.close();
					return 0;
				}
			}
			int bytesRead = in.read(buf, off, len);
			this.debugBuffer(buf);
			bytesInChunk -= bytesRead;
			if (bytesInChunk == 0) {
				in.skip(2);
			}

			return bytesRead;
		} catch (Exception e) {
			throw new TTransportException(e);
		}
	}

	public void debugBuffer(byte[] buf) {
		if (!LOGGER.isDebugEnabled()) {
			return;
		}
		StringBuffer buffer = new StringBuffer();
		buffer.append("BUFFER >>");
		for (int i = 0; i < buf.length; i++) {
			buffer.append(buf[i] + " ");
		}
		buffer.append("<<");
		LOGGER.debug(buffer.toString());
	}

	@Override
	public void write(byte[] buf, int off, int len) throws TTransportException {
		try {
			obuffer.write(buf, off, len);
		} catch (Exception e) {
			throw new TTransportException(e);
		}
	}

	public TFullDuplexHttpClient(String host, int port, String resource,
			String method) {
		super();
		this.host = host;
		this.port = port;
		this.method = method;
		this.resource = resource;
	}

	@Override
	public boolean peek() {
		try {
			return (in.available() > 0);
		} catch (IOException e) {
			LOGGER.warn("failed to peek", e);
			return false;
		}
	}

	public void open() {
		try {
			this.socket = new Socket(host, port);
			this.out = this.socket.getOutputStream();
			this.in = this.socket.getInputStream();

			out
					.write(("GET " + resource + " HTTP/1.1\n" + "Host: " + host
							+ ":" + port + "\r\n"
							+ "User-Agent: ThriftJavaFullDuplex\r\n"
							+ "Transfer-Encoding: chunked\r\n"
							+ "content-type: application/x-thrift\r\n" + "Accept: */*\r\n\r\n")
							.getBytes());
			out.flush();
			this.isOpen = true;
		} catch (Exception e) {
			LOGGER.error("failed to open", e);
		}

	}

	@Override
	public void flush() throws TTransportException {
		try {
			byte[] data = obuffer.toByteArray();
			out.write(Integer.toHexString(data.length).getBytes());
			out.write(CRLF);
			out.write(data, 0, data.length);
			out.write(CRLF);
			out.flush();
			obuffer.reset();
		} catch (Exception e) {
			throw new TTransportException(e);
		}
	}

}
