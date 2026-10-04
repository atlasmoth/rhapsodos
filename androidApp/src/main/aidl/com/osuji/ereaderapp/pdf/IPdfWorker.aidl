package com.osuji.ereaderapp.pdf;
import android.os.ParcelFileDescriptor;
interface IPdfWorker {
    ParcelFileDescriptor request(in ParcelFileDescriptor pdf, String identity, String operation, int pageIndex, int targetWidth);
}
