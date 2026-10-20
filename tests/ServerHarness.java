package io.github.einktransfer;
import java.io.*;
/** 让主机集成测试和设备共用实际 HTTP 实现。 */
public final class ServerHarness {
    public static void main(String[] args) throws Exception {
        TransferServer server = new TransferServer(new File(args[0]), new File(args[0]).getParentFile(), "127.0.0.1", 0,
            name -> new FileInputStream(new File(args[1], name)), null);
        System.out.println(server.port()); System.out.flush();
        System.in.read(); server.close();
    }
}
