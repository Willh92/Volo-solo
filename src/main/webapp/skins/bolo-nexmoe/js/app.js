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
$("table").each(function () {
    var $table = $(this);
    if ($table.find("th, thead").length) return;
    var $cells = $table.find("td");
    if ($cells.length && $cells.filter(function () { return $(this).find("img").length > 0; }).length === $cells.length) {
        $table.addClass("nexmoe-album");
    }
});

$("#nexmoe-content img").each(function () {
    if (!this.closest('.nexmoe-post-cover')) this.loading = 'lazy';
    this.decoding = 'async';
});


$("#nexmoe-sidebar a").addClass("mdui-ripple");
if (window.mdui && typeof window.mdui.mutation === "function") {
    window.mdui.mutation();
}
