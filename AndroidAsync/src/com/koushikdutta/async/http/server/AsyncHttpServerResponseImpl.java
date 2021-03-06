package com.koushikdutta.async.http.server;

import android.text.TextUtils;

import com.koushikdutta.async.*;
import com.koushikdutta.async.callback.CompletedCallback;
import com.koushikdutta.async.callback.WritableCallback;
import com.koushikdutta.async.http.filter.ChunkedOutputFilter;
import com.koushikdutta.async.http.libcore.RawHeaders;
import com.koushikdutta.async.http.libcore.ResponseHeaders;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;

public class AsyncHttpServerResponseImpl implements AsyncHttpServerResponse {
    private RawHeaders mRawHeaders = new RawHeaders();
    private int mContentLength = -1;
    private ResponseHeaders mHeaders = new ResponseHeaders(null, mRawHeaders);
    
    @Override
    public ResponseHeaders getHeaders() {
        return mHeaders;
    }
    
    public AsyncSocket getSocket() {
        return mSocket;
    }

    AsyncSocket mSocket;
    AsyncHttpServerRequestImpl mRequest;
    AsyncHttpServerResponseImpl(AsyncSocket socket, AsyncHttpServerRequestImpl req) {
        mSocket = socket;
        mRequest = req;
        mRawHeaders.set("Connection", "Keep-Alive");
    }
    
    @Override
    public void write(ByteBuffer bb) {
        if (bb.remaining() == 0)
            return;
        writeInternal(bb);
    }

    @Override
    public void write(ByteBufferList bb) {
        if (bb.remaining() == 0)
            return;
        writeInternal(bb);
    }

    private void writeInternal(ByteBuffer bb) {
        assert !mEnded;
        if (!mHasWritten) {
            initFirstWrite();
            return;
        }
        mSink.write(bb);
    }

    private void writeInternal(ByteBufferList bb) {
        assert !mEnded;
        if (!mHasWritten) {
            initFirstWrite();
            return;
        }
        mSink.write(bb);
    }

    boolean mHasWritten = false;
    DataSink mSink;
    void initFirstWrite() {
        if (mHasWritten)
            return;

        mHasWritten = true;
        assert null != mRawHeaders.getStatusLine();
        if (mContentLength < 0) {
            mRawHeaders.set("Transfer-Encoding", "Chunked");
            mSink = new ChunkedOutputFilter(mSocket);
        }
        else {
            mSink = mSocket;
        }
        writeHead();
    }

    @Override
    public void setWriteableCallback(WritableCallback handler) {
        initFirstWrite();
        mSink.setWriteableCallback(handler);
    }

    @Override
    public WritableCallback getWriteableCallback() {
        initFirstWrite();
        return mSink.getWriteableCallback();
    }

    @Override
    public void end() {
        if (null == mRawHeaders.get("Transfer-Encoding") && !mHasWritten) {
            send("text/html", "");
            onEnd();
            return;
        }
        initFirstWrite();

        ((ChunkedOutputFilter)mSink).setMaxBuffer(Integer.MAX_VALUE);
        mSink.write(new ByteBufferList());

        onEnd();
    }

    private boolean mHeadWritten = false;
    @Override
    public void writeHead() {
        assert !mHeadWritten;
        mHeadWritten = true;
        Util.writeAll(mSocket, mRawHeaders.toHeaderString().getBytes(), new CompletedCallback() {
            @Override
            public void onCompleted(Exception ex) {
                WritableCallback writableCallback = getWriteableCallback();
                if (writableCallback != null)
                    writableCallback.onWriteable();
            }
        });
    }

    @Override
    public void setContentType(String contentType) {
        assert !mHeadWritten;
        mRawHeaders.set("Content-Type", contentType);
    }
    
    public void send(String contentType, final String string) {
        try {
            if (mRawHeaders.getStatusLine() == null)
                responseCode(200);
            assert mContentLength < 0;
            byte[] bytes = string.getBytes("UTF-8");
            mContentLength = bytes.length;
            mRawHeaders.set("Content-Length", Integer.toString(bytes.length));
            mRawHeaders.set("Content-Type", contentType);

            Util.writeAll(this, string.getBytes(), new CompletedCallback() {
                @Override
                public void onCompleted(Exception ex) {
                    onEnd();
                }
            });
        }
        catch (UnsupportedEncodingException e) {
            assert false;
        }
    }
    
    boolean mEnded;
    protected void onEnd() {
        mEnded = true;
    }
    
    protected void report(Exception e) {
    }


    @Override
    public void send(String string) {
        responseCode(200);
        send("text/html; charset=utf8", string);
    }

    @Override
    public void send(JSONObject json) {
        send("application/json; charset=utf8", json.toString());
    }
    
    public void sendFile(File file) {
        int start = 0;
        int end = (int)file.length();

        String range = mRequest.getHeaders().getHeaders().get("Range");
        if (range != null) {
            String[] parts = range.split("=");
            if (parts.length != 2 || !"bytes".equals(parts[0])) {
                // Requested range not satisfiable
                responseCode(416);
                end();
                return;
            }

            parts = parts[1].split("-");
            try {
                if (parts.length > 2)
                    throw new Exception();
                if (!TextUtils.isEmpty(parts[0]))
                    start = Integer.parseInt(parts[0]);
                if (parts.length == 2 && !TextUtils.isEmpty(parts[1]))
                    end = Integer.parseInt(parts[1]);
                else if (start != 0)
                    end = (int)file.length();
                else
                    end = Math.min((int)file.length(), start + 50000);

                responseCode(206);
                getHeaders().getHeaders().set("Content-Range", String.format("bytes %d-%d/%d", start, end - 1, file.length()));
            }
            catch (Exception e) {
                responseCode(416);
                end();
                return;
            }
        }
        try {
            FileInputStream fin = new FileInputStream(file);
            if (start != fin.skip(start))
                throw new Exception("skip failed to skip requested amount");
            mRawHeaders.set("Content-Type", AsyncHttpServer.getContentType(file.getAbsolutePath()));
            mContentLength = end - start;
            mRawHeaders.set("Content-Length", "" + mContentLength);
            if (getHeaders().getHeaders().getStatusLine() == null)
                responseCode(200);
            Util.pump(fin, end - start, this, new CompletedCallback() {
                @Override
                public void onCompleted(Exception ex) {
                    end();
                }
            });
        }
        catch (Exception e) {
            responseCode(404);
            end();
        }
    }

    @Override
    public void responseCode(int code) {
        String status = AsyncHttpServer.getResponseCodeDescription(code);
        mRawHeaders.setStatusLine(String.format("HTTP/1.1 %d %s", code, status));
    }

    @Override
    public void redirect(String location) {
        responseCode(302);
        mRawHeaders.set("Location", location);
        end();
    }

    @Override
    public void onCompleted(Exception ex) {
        if (ex != null) {
            ex.printStackTrace();
        }
        end();
    }

    @Override
    public boolean isOpen() {
        return mSink.isOpen();
    }

    @Override
    public void close() {
        end();
        mSink.close();
    }

    @Override
    public void setClosedCallback(CompletedCallback handler) {
        mSink.setClosedCallback(handler);
    }

    @Override
    public CompletedCallback getClosedCallback() {
        return mSink.getClosedCallback();
    }

    @Override
    public AsyncServer getServer() {
        return mSocket.getServer();
    }
}
