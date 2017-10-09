/*
 * Copyright (C) 2016-2017 ActionTech.
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher.
 */

package com.actiontech.dble.backend.mysql.nio.handler.query.impl.subquery;

import com.actiontech.dble.DbleServer;
import com.actiontech.dble.backend.BackendConnection;
import com.actiontech.dble.backend.mysql.nio.handler.util.HandlerTool;
import com.actiontech.dble.config.ErrorCode;
import com.actiontech.dble.net.mysql.FieldPacket;
import com.actiontech.dble.net.mysql.RowDataPacket;
import com.actiontech.dble.plan.common.field.Field;
import com.actiontech.dble.plan.common.item.Item;
import com.actiontech.dble.plan.common.item.subquery.ItemInSubQuery;
import com.actiontech.dble.server.NonBlockingSession;

import java.util.Collections;
import java.util.List;

public class InSubQueryHandler extends SubQueryHandler {
    private int maxPartSize = 2000;
    private int maxConnSize = 4;
    private int rowCount = 0;
    private Field sourceField;
    private ItemInSubQuery itemSubQuery;
    public InSubQueryHandler(long id, NonBlockingSession session, ItemInSubQuery itemSubQuery) {
        super(id, session);
        this.itemSubQuery = itemSubQuery;
        this.maxPartSize = DbleServer.getInstance().getConfig().getSystem().getNestLoopRowsSize();
        this.maxConnSize = DbleServer.getInstance().getConfig().getSystem().getNestLoopConnSize();
    }

    @Override
    public void fieldEofResponse(byte[] headerNull, List<byte[]> fieldsNull, List<FieldPacket> fieldPackets,
                                 byte[] eofNull, boolean isLeft, BackendConnection conn) {
        if (terminate.get()) {
            return;
        }
        lock.lock();
        try {
            // create field for first time
            if (this.fieldPackets.isEmpty()) {
                this.fieldPackets = fieldPackets;
                sourceField = HandlerTool.createField(this.fieldPackets.get(0));
                Item select = itemSubQuery.getSelect();
                select.setPushDownName(select.getAlias());
                Item tmpItem = HandlerTool.createItem(select, Collections.singletonList(this.sourceField), 0, isAllPushDown(), type());
                itemSubQuery.setFiled(tmpItem);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean rowResponse(byte[] rowNull, RowDataPacket rowPacket, boolean isLeft, BackendConnection conn) {
        lock.lock();
        try {
            if (terminate.get()) {
                return true;
            }
            if (++rowCount > maxPartSize * maxConnSize) {
                String errMessage = "sub query too much rows!";
                LOGGER.warn(errMessage);
                genErrorPackage(ErrorCode.ER_UNKNOWN_ERROR, errMessage);
                conn.close(errMessage);
                try {
                    tempDoneCallBack.call();
                } catch (Exception callback) {
                    LOGGER.warn("callback exception!", callback);
                }
                return true;
            }
            RowDataPacket row = rowPacket;
            if (row == null) {
                row = new RowDataPacket(this.fieldPackets.size());
                row.read(rowNull);
            }
            sourceField.setPtr(row.getValue(0));
            Item value = itemSubQuery.getFiled().getResultItem();
            if (value != null) {
                itemSubQuery.getValue().add(value);
            }
        } finally {
            lock.unlock();
        }
        return false;
    }


    @Override
    public HandlerType type() {
        return HandlerType.IN_SUB_QUERY;
    }

}
