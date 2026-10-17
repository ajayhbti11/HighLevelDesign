package com.searchengine.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.searchengine.common.DocId;
import com.searchengine.common.Posting;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link PostingList} (design §4, Req 5.1, 5.2, 5.5). */
class PostingListTest {

    @Test
    void keepsPostingsInAscendingDocIdOrder() {
        PostingList list = new PostingList();
        list.addPosting(DocId.of(5), List.of(1));
        list.addPosting(DocId.of(2), List.of(0));
        list.addPosting(DocId.of(9), List.of(3));

        List<Posting> postings = list.postings();
        assertEquals(
                List.of(DocId.of(2), DocId.of(5), DocId.of(9)),
                postings.stream().map(Posting::docId).toList());
    }

    @Test
    void mergesPositionsForSameDocIdAndKeepsThemAscending() {
        PostingList list = new PostingList();
        list.addPosting(DocId.of(1), List.of(3, 7));
        list.addPosting(DocId.of(1), List.of(1, 5));

        assertEquals(1, list.size());
        assertEquals(List.of(1, 3, 5, 7), list.postings().get(0).positions());
    }

    @Test
    void removeDocDeletesAllReferences() {
        PostingList list = new PostingList();
        list.addPosting(DocId.of(1), List.of(0));
        list.addPosting(DocId.of(2), List.of(0));

        assertTrue(list.removeDoc(DocId.of(1)));
        assertFalse(list.contains(DocId.of(1)));
        assertEquals(1, list.size());
        assertFalse(list.removeDoc(DocId.of(1)));
    }

    @Test
    void emptyByDefault() {
        PostingList list = new PostingList();
        assertTrue(list.isEmpty());
        assertEquals(0, list.size());
    }
}
