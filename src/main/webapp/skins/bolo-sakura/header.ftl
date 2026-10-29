<#--

    Bolo - A stable and beautiful blogging system based in Solo.
    Copyright (c) 2020-present, https://github.com/bolo-blog

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.

-->
<!DOCTYPE html>
<html lang="en-US">
<head>
    <@head title="${blogTitle}">
        <link media="all" href="${staticServePath}/skins/${skinDirName}/css/base.css?${staticResourceVersion}"
              rel="stylesheet"/>
        <link media="all" href="${staticServePath}/skins/${skinDirName}/css/style.css?${staticResourceVersion}"
              rel="stylesheet"/>
        <link rel="stylesheet" href="${staticServePath}/js/lib/font-awesome/css/font-awesome.min.css"/>
        <style type="text/css">
            tr th, tr td {
                border: 1px solid #1d1f21;
            }

            .site-top .lower nav {
                display: block !important;
            }

            .author-profile i, .post-like a, .post-share .show-share, .sub-text, .we-info a, span.sitename, .post-more i:hover, #pagination a:hover, .post-content a:hover, .float-content i:hover {
                color: #FE9600
            }

            .feature i, /*.feature-title span ,*/
            .download, .navigator i:hover, .links ul li:before, .ar-time i, span.ar-circle, .object, .comment .comment-reply-link, .siren-checkbox-radio:checked + .siren-checkbox-radioInput:after {
                background: #FE9600
            }

            ::-webkit-scrollbar-thumb {
                background: #FE9600
            }

            .download, .navigator i:hover, .link-title, .links ul li:hover, #pagination a:hover, .comment-respond input[type='submit']:hover {
                border-color: #FE9600
            }

            .entry-content a:hover, .site-info a:hover, .comment h4 a, #comments-navi a.prev, #comments-navi a.next, .comment h4 a:hover, .site-top ul li a:hover, .entry-title a:hover, #archives-temp h3, span.page-numbers.current, .sorry li a:hover, .site-title a:hover, i.iconfont.js-toggle-search.iconsearch:hover, .comment-respond input[type='submit']:hover {
                color: #FE9600
            }

            .comments .comments-main {
                display: block !important;
            }

            .comments .comments-hidden {
                display: none !important;
            }

            .centerbg {
                background-image: linear-gradient(135deg, #eed8df, #e4edf2);
                background-position: center center;
                background-attachment: inherit;
            }
        </style>
        <script type="text/javascript">if (!!window.ActiveXObject || "ActiveXObject" in window) { //is IE?
                alert('朋友，IE浏览器未适配哦~（QQ、360浏览器请关闭 IE 模式访问~）');
            }</script>
        <script src="${staticServePath}/js/lib/jquery/jquery.min.js"></script>
        <link rel="stylesheet" href="${staticServePath}/js/lib/fancybox/jquery.fancybox.min.css"/>
        <script src="${staticServePath}/js/lib/fancybox/jquery.fancybox.min.js"></script>
    </@head>
</head>
