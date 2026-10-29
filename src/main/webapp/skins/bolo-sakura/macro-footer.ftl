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
<footer id="colophon" class="site-footer" role="contentinfo">
    <div class="site-info">
        <div class="footertext">
            <p class="foo-logo"
               style="background-image: url('${staticServePath}/images/volo-mark.svg');">
            </p>
            Powered by <a href="https://github.com/Willh92/Volo-solo" target="_blank" rel="noopener noreferrer">Volo</a><br>
            Theme <a rel="friend noopener noreferrer" href="https://github.com/mashirozx/Sakura" target="_blank">Sakura</a> by <a href="https://hello.2heng.xin/@mashiro" target="_blank" rel="noopener noreferrer">Mashiro</a><br>
            <small>Adapted from <a href="https://github.com/bolo-blog/bolo-solo" target="_blank" rel="noopener noreferrer">Bolo</a> <i class="fa fa-heart" aria-hidden="true"></i></small><br>
            © ${year} <a href="${servePath}">${blogTitle}</a><br>
            <span>${viewLabel}</span>
            ${statistic.statisticBlogViewCount}
            <span>${articleLabel}</span>
            ${statistic.statisticPublishedBlogArticleCount}
            <#if interactive == "on">
            <span>${commentLabel}</span>
            ${statistic.statisticPublishedBlogCommentCount}
            </#if>
            <br>
            ${footerContent}
        </div>
        <div class="footer-device">
        </div>
    </div>
</footer>
<div class="openNav no-select">
    <div class="iconflat no-select">
        <div class="icon">
        </div>
    </div>
    <div class="site-branding">
        <h1 class="site-title"><a href="${servePath}/" title="${blogTitle?html}">${blogTitle?html}</a></h1>
    </div>
    <div class="skin-theme-slot sakura-theme-mobile" data-bolo-theme-slot></div>
</div>
