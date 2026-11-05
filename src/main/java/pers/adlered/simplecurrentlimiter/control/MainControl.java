/*
 * Bolo - A stable and beautiful blogging system based in Solo.
 * Copyright (c) 2020-present, https://github.com/bolo-blog
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package pers.adlered.simplecurrentlimiter.control;

import pers.adlered.simplecurrentlimiter.cache.MainCache;
import pers.adlered.simplecurrentlimiter.cache.pair.CachePair;

/**
 * <h3>SimpleCurrentLimiter</h3>
 * <p>底层数据控制</p>
 *
 * @author : https://github.com/adlered
 * @date : 2019-10-10 22:55
 **/
public class MainControl extends MainCache {
    public static final int MAX_CACHE_ENTRIES = 10_000;
    private final java.util.concurrent.atomic.AtomicBoolean pruning = new java.util.concurrent.atomic.AtomicBoolean(false);

    public boolean write(String str) {
        if (str == null) {
            return true;
        }
        final long currentTimeStamp = System.currentTimeMillis();
        final CachePair existing = this.cachePairMap.get(str);
        if (existing != null) {
            return existing.incrementAndGet(currentTimeStamp, this.expireTime) <= this.frequencyTime;
        }
        if (this.cachePairMap.size() >= MAX_CACHE_ENTRIES) {
            if (!makeRoomForNewKey(currentTimeStamp)) {
                return false;
            }
        }
        final CachePair fresh = new CachePair(0L, currentTimeStamp);
        final CachePair actual = this.cachePairMap.putIfAbsent(str, fresh);
        final CachePair target = actual != null ? actual : fresh;
        final long count = target.incrementAndGet(currentTimeStamp, this.expireTime);
        if (actual == null && this.cachePairMap.size() > MAX_CACHE_ENTRIES) {
            makeRoomForNewKey(currentTimeStamp);
        }
        return count <= this.frequencyTime;
    }

    private boolean makeRoomForNewKey(final long currentTimeStamp) {
        if (this.cachePairMap.size() < MAX_CACHE_ENTRIES) {
            return true;
        }
        if (!this.pruning.compareAndSet(false, true)) {
            return this.cachePairMap.size() < MAX_CACHE_ENTRIES;
        }
        try {
            if (this.cachePairMap.size() < MAX_CACHE_ENTRIES) {
                return true;
            }
            final long expire = this.expireTime;
            if (expire != -1) {
                this.cachePairMap.entrySet().removeIf(entry ->
                        (currentTimeStamp - entry.getValue().getTimeStamp()) > expire);
            }
            if (this.cachePairMap.size() < MAX_CACHE_ENTRIES) {
                return true;
            }
            // Never reset actively rate-limited keys during a spoofed-IP flood.
            int evicted = 0;
            final var iterator = this.cachePairMap.entrySet().iterator();
            while (iterator.hasNext() && (this.cachePairMap.size() >= MAX_CACHE_ENTRIES || evicted < 256)) {
                final var entry = iterator.next();
                if (this.frequencyTime < 0 || entry.getValue().getFrequency() <= this.frequencyTime) {
                    iterator.remove();
                    evicted++;
                }
            }
            return this.cachePairMap.size() < MAX_CACHE_ENTRIES;
        } finally {
            this.pruning.set(false);
        }
    }

    public void setFrequencyTime(long frequencyTime) {
        this.frequencyTime = frequencyTime;
    }

    public void setExpireTimeMilli(long timeMilli) {
        this.expireTime = timeMilli;
    }

    public void setExpireTimeSecond(long timeSecond) {
        this.expireTime = timeSecond * 1000;
    }

    public void setExpireTimeMin(long timeMin) {
        this.expireTime = timeMin * 60000;
    }

    public void setExpireTimeHour(long timeHour) {
        this.expireTime = timeHour * 3600000;
    }
}
