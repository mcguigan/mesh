/**
 * ADOBE SYSTEMS INCORPORATED
 * Copyright 2009-2013 Adobe Systems Incorporated
 * All Rights Reserved.
 *
 * NOTICE: Adobe permits you to use, modify, and distribute
 * this file in accordance with the terms of the MIT license,
 * a copy of which can be found in the LICENSE.txt file or at
 * http://opensource.org/licenses/MIT.
 */
package runtime.rep.list;

import runtime.rep.Lambda;
import runtime.rep.map.MapValue;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Sequence of lists representing a single list.
 *
 * @author Basil Hosmer
 */
public final class ChainedLists implements ListValue
{
    /**
     * take advantage of some degenerate cases
     */
    public static ListValue create(final ListValue lists)
    {
        final int nlists = lists.size();

        // early trap avoids computation for pair case
        if (nlists == 2)
            return ChainedListPair.create(
                (ListValue)lists.get(0), (ListValue)lists.get(1));

        int size = 0;
        boolean isreg = true;

        int necount = 0;
        int nefirst = -1;

        final int bases[] = new int[nlists + 1];
        final int stride;
        {
            int i = 0;
            int lastsize = 0;

            for (final Object list : lists)
            {
                bases[i] = size;

                final int cursize = ((ListValue)list).size();
                final int nextbase = size + cursize;

                if (nextbase > size)
                {
                    necount++;

                    if (nefirst == -1)
                        nefirst = i;

                    if (i == 0)
                        lastsize = cursize;
                    else if (cursize != lastsize)
                        isreg = false;

                    size = nextbase;
                }

                i++;
            }

            bases[i] = size;

            stride = isreg ? lastsize : -1;
        }

        switch (necount)
        {
            case 0:
                return PersistentList.EMPTY;

            case 1:
                return (ListValue)lists.get(nefirst);

            default:
            {
                if (necount == nlists)
                {
                    // note: we took care of the pair case up top
                    return isreg ? new MatrixList(lists, stride) :
                        new ChainedLists(lists, bases);

                }
                else
                {
                    final PersistentList nelists =
                        PersistentList.alloc(necount);

                    final int nebases[] = new int[necount + 1];

                    int i = 0;

                    int li = 0;

                    for (final Object list : lists)
                    {
                        if (bases[i + 1] > bases[i])
                        {
                            nebases[li] = bases[i];
                            nelists.updateUnsafe(li, list);
                            li++;
                        }

                        i++;
                    }

                    nebases[li] = bases[i];

                    return
                        necount == 2 ?
                            ChainedListPair.create(
                                (ListValue)nelists.get(0), (ListValue)nelists.get(1)) :
                            isreg ? new MatrixList(nelists, stride) :
                                new ChainedLists(nelists, nebases);
                }
            }
        }
    }

    //
    // instance
    //

    private final ListValue lists;
    private final int[] bases;
    private final int size;

    private ChainedLists(final ListValue lists, final int[] bases)
    {
        this.lists = lists;
        this.bases = bases;
        this.size = bases[lists.size()];
    }

    public int size()
    {
        return size;
    }

    public Object get(final int index)
    {
        if (index < 0 || index >= size)
            throw new IndexOutOfBoundsException();

        final int li = listIndexForItemIndex(index);
        return ((ListValue)lists.get(li)).get(index - bases[li]);
    }

    /**
     * Return position of list in {@link #lists} containing
     * the given item index.
     */
    private int listIndexForItemIndex(final int index)
    {
        int n = bases.length / 2;
        int i = n;
        do
        {
            n = Math.max(n / 2, 1);
            final boolean too_low = bases[i + 1] <= index;
            final boolean too_high = bases[i] > index;
            if (too_low)
                i += n;
            else if (too_high)
                i -= n;
            else
                return i;
        }
        while (true);
    }

    public int find(final Object value)
    {
        int base = 0;
        for (final Object item : lists)
        {
            final ListValue list = (ListValue)item;
            final int i = list.find(value);
            final int listSize = list.size();
            if (i < listSize)
                return base + i;
            base += listSize;
        }
        return base;
    }

    public ListValue append(final Object value)
    {
        final int li = lists.size() - 1;
        final ListValue list = (ListValue)lists.get(li);

        final int length = bases.length;
        final int[] newbases = new int[length];
        System.arraycopy(bases, 0, newbases, 0, length);
        newbases[length - 1]++;

        return new ChainedLists(lists.update(li, list.append(value)), newbases);
    }

    public ListValue update(final int index, final Object value)
    {
        if (index < 0 || index >= size)
            throw new IndexOutOfBoundsException();

        final int li = listIndexForItemIndex(index);
        final ListValue list = (ListValue)lists.get(li);

        return new ChainedLists(
            lists.update(li, list.update(index - bases[li], value)), bases);
    }

    public ListValue subList(final int from, final int to)
    {
        return Sublist.create(this, from, to);
    }

    public ListValue apply(final Lambda f)
    {
        final PersistentList result = PersistentList.alloc(size);

        int i = 0;
        for (final Object list : lists)
            for (final Object item : (ListValue)list)
                result.updateUnsafe(i++, f.apply(item));

        return result;
    }

    public void run(final Lambda f)
    {
        for (final Object list : lists)
            ((ListValue)list).run(f);
    }

    public ListValue select(final ListValue base)
    {
        final PersistentList result = PersistentList.alloc(size);

        int i = 0;
        for (final Object list : lists)
            for (final Object item : (ListValue)list)
                result.updateUnsafe(i++, base.get((Integer)item));

        return result;
    }

    public ListValue select(final MapValue map)
    {
        final PersistentList result = PersistentList.alloc(size);

        int i = 0;
        for (final Object list : lists)
            for (final Object item : (ListValue)list)
                result.updateUnsafe(i++, map.get(item));

        return result;
    }

    // Iterable

    public Iterator<Object> iterator()
    {
        return iterator(0, size);
    }

    public Iterator<Object> iterator(final int from, final int to)
    {
        final int li = from == 0 ? 0 : listIndexForItemIndex(from);

        return new Iterator<Object>()
        {
            final int base = bases[li];
            final Iterator<?> listIter = lists.iterator(li, lists.size());

            Iterator<?> itemIter = ((ListValue)listIter.next()).
                iterator(from - base, bases[li + 1] - base);

            int i = from;

            public final boolean hasNext()
            {
                return i < to;
            }

            public final Object next()
            {
                if (i == to)
                    throw new NoSuchElementException();

                if (!itemIter.hasNext())
                    itemIter = ((ListValue)listIter.next()).iterator();

                i++;

                return itemIter.next();
            }

            public final void remove()
            {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Override
    public final boolean equals(final Object obj)
    {
        if (obj == this)
        {
            return true;
        }
        else if (obj instanceof ListValue)
        {
            final ListValue other = (ListValue)obj;

            if (size() != other.size())
                return false;

            final Iterator<?> e1 = iterator();
            final Iterator<?> e2 = other.iterator();

            while (e1.hasNext() && e2.hasNext())
                if (!e1.next().equals(e2.next()))
                    return false;

            return true;
        }
        else
        {
            return false;
        }
    }

    @Override
    public final int hashCode()
    {
        int hash = 1;

        for (final Object obj : this)
            hash = 31 * hash + obj.hashCode();

        return hash;
    }
}
