package com.github.tvbox.osc.util;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.util.LOG;

import java.io.DataInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Random;

public class UA {
	
	public static String random() {
        try {
            InputStream fis = App.getInstance().getAssets().open("ua.db");
            DataInputStream dis = new DataInputStream(fis);
            int len = dis.readInt();
            int random = new Random().nextInt(len);
            dis.skipBytes(random * 4);
            int offset = dis.readInt();
            dis.skipBytes((len - 1 - random) * 4 + offset);
            String s = dis.readUTF();
            return s;
        } catch (FileNotFoundException e) {
            LOG.e(e);
        } catch (IOException e) {
            LOG.e(e);
        }
        return "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.114 Safari/537.36";
    }
}