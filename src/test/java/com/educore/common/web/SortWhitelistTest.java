package com.educore.common.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SortWhitelistTest {

    private static final SortWhitelist STUDENTS = SortWhitelist.of("firstName", Sort.by("id"))
            .allow("firstName", "firstName")
            .allow("number", "studentNumber")
            .allow("id", "id");

    @Test
    void defaultKeyAscendingWithTieBreakerWhenNothingIsRequested() {
        assertThat(orders(STUDENTS.resolve(null, null))).containsExactly("firstName:ASC", "id:ASC");
        assertThat(orders(STUDENTS.resolve("", " "))).containsExactly("firstName:ASC", "id:ASC");
    }

    @Test
    void requestedKeyIsMappedToTheWhitelistedPropertyNotTheRawInput() {
        assertThat(orders(STUDENTS.resolve("number", "desc"))).containsExactly("studentNumber:DESC", "id:ASC");
    }

    @Test
    void directionIsCaseInsensitive() {
        assertThat(orders(STUDENTS.resolve("firstName", "DESC"))).containsExactly("firstName:DESC", "id:ASC");
        assertThat(orders(STUDENTS.resolve("firstName", "Asc"))).containsExactly("firstName:ASC", "id:ASC");
    }

    @Test
    void tieBreakerIsNotRepeatedWhenItIsTheRequestedKey() {
        assertThat(orders(STUDENTS.resolve("id", "desc"))).containsExactly("id:DESC");
    }

    @ParameterizedTest
    @ValueSource(strings = {"password", "studentNumber", "firstname", "role", "firstName;drop", "a.b",
            "firstName,desc", "(select 1)"})
    void unknownKeysAreRejectedWithSortInvalid(String key) {
        assertSortInvalid(() -> STUDENTS.resolve(key, "asc"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"up", "descending", "1", "asc;", "ASC NULLS FIRST"})
    void unknownDirectionsAreRejectedWithSortInvalid(String direction) {
        assertSortInvalid(() -> STUDENTS.resolve("firstName", direction));
        assertSortInvalid(() -> SortWhitelist.direction(direction));
    }

    @Test
    void whitelistIsImmutableAndListsItsKeys() {
        SortWhitelist base = SortWhitelist.of("id", Sort.by("id")).allow("id", "id");
        SortWhitelist extended = base.allow("name", "name");

        assertThat(base.keys()).containsExactly("id");
        assertThat(extended.keys()).containsExactlyInAnyOrder("id", "name");
        assertSortInvalid(() -> base.resolve("name", null));
    }

    @Test
    void pagingRejectsOutOfRangeValuesInsteadOfCappingThem() {
        assertThat(Paging.of(0, 100, Sort.unsorted()).getPageSize()).isEqualTo(100);
        assertThat(Paging.of(7, 1, Sort.unsorted()).getPageNumber()).isEqualTo(7);
        for (int[] invalid : new int[][]{{-1, 10}, {0, 0}, {0, 101}, {0, Integer.MAX_VALUE}, {Integer.MIN_VALUE, 5}}) {
            assertThatThrownBy(() -> Paging.of(invalid[0], invalid[1], Sort.unsorted()))
                    .isInstanceOfSatisfying(ApiProblemException.class, problem -> {
                        assertThat(problem.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(problem.code()).isEqualTo(Problems.INVALID_REQUEST);
                        assertThat(problem.properties()).containsKey(Problems.ERRORS);
                    });
        }
    }

    @Test
    void pagingRejectsRowOffsetsBeyondTheIntRange() {
        int lastPage = Integer.MAX_VALUE / 100;
        assertThat(Paging.of(lastPage, 100, Sort.unsorted()).getOffset()).isEqualTo((long) lastPage * 100);
        assertThat(Paging.of(Integer.MAX_VALUE, 1, Sort.unsorted()).getOffset()).isEqualTo(Integer.MAX_VALUE);
        for (int[] overflowing : new int[][]{{lastPage + 1, 100}, {Integer.MAX_VALUE, 2}, {Integer.MAX_VALUE / 2 + 1, 2}}) {
            assertThatThrownBy(() -> Paging.of(overflowing[0], overflowing[1], Sort.unsorted()))
                    .isInstanceOfSatisfying(ApiProblemException.class, problem -> {
                        assertThat(problem.code()).isEqualTo(Problems.INVALID_REQUEST);
                        assertThat(problem.properties().get(Problems.ERRORS))
                                .isEqualTo(List.of(new FieldViolation("page", "range")));
                    });
        }
    }

    private static void assertSortInvalid(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiProblemException.class, problem -> {
            assertThat(problem.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(problem.code()).isEqualTo("sort/invalid");
        });
    }

    private static List<String> orders(Sort sort) {
        return sort.stream().map(order -> order.getProperty() + ":" + order.getDirection()).toList();
    }
}
